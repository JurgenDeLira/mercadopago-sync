"""
etl_banorte.py
Battery Plus Automotriz — Pipeline de Conciliación
ETL: Limpieza, normalización y carga a SQL Server del reporte Banorte (.xlsx)

Uso:
    python etl_banorte.py                          # busca el .xlsx más reciente en C:/Scripts
    python etl_banorte.py ruta\al\reporte.xlsx     # archivo específico
    python etl_banorte.py --solo-csv               # genera CSV sin tocar la BD
    python etl_banorte.py --solo-db                # carga a BD sin generar CSV

Dependencias:
    pip install pandas openpyxl pyodbc
"""

import argparse
import glob
import os
import sys
from datetime import datetime

import pandas as pd
import pyodbc

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------
CARPETA_DEFAULT = r"C:/Scripts"

DB_SERVER   = "192.168.1.123,1433"
DB_NAME     = "MercadoPagoSync"
DB_USER     = "surveyuser"
DB_PASSWORD = "a1b2c3"
DB_TABLE    = "dbo.pagos_banorte"

# ---------------------------------------------------------------------------
# Mapeo columnas Excel → nombres internos
# ---------------------------------------------------------------------------
COLUMNAS_REQUERIDAS = {
    "Afiliación":                 "afiliacion",
    "Terminal ID":                "terminal",
    "Estatus de Transacción":     "status",
    "Tipo transaccion":           "payment_method",
    "Tipo de Tarjeta":            "payment_type",
    "Tipo de Plan":               "tipo_plan",
    "Número de Pagos":            "installments",
    "Código Autorización":        "codigo_autorizacion",
    "Monto de Transacción Signo": "monto",
    "Total de Comisiones":        "comision_banorte_raw",
    "Fecha Transacción":          "fecha_transaccion",
    "Fecha Aplicación":           "fecha_deposito",
    "Moneda":                     "moneda",
    "IVA de Pagos Diferidos":     "iva_pagos_diferidos",  # solo para validación interna
}

COLUMNAS_EXCLUIDAS = [
    "Número de Tarjeta",         # dato sensible del cliente
    "Referencia Interbancaria",  # NO es la Referencia de Verina — excluir para evitar confusiones
]


# ---------------------------------------------------------------------------
# Lectura
# ---------------------------------------------------------------------------
def buscar_excel_default(carpeta: str) -> str:
    archivos = glob.glob(os.path.join(carpeta, "*.xlsx"))
    if not archivos:
        sys.exit(f"[ERROR] No se encontró ningún .xlsx en {carpeta}")
    reciente = max(archivos, key=os.path.getmtime)
    print(f"[AUTO] Usando archivo más reciente: {reciente}")
    return reciente


def leer_reporte(ruta: str) -> pd.DataFrame:
    try:
        df = pd.read_excel(ruta)
    except Exception as e:
        sys.exit(f"[ERROR] No se pudo leer el archivo: {e}")

    # Eliminar columnas excluidas antes de cualquier otra operación
    presentes = [c for c in COLUMNAS_EXCLUIDAS if c in df.columns]
    if presentes:
        df = df.drop(columns=presentes)
        for c in presentes:
            print(f"[OK] Columna excluida '{c}' eliminada.")

    # Verificar columnas requeridas
    faltantes = [c for c in COLUMNAS_REQUERIDAS if c not in df.columns]
    if faltantes:
        sys.exit(f"[ERROR] Columnas no encontradas en el reporte: {faltantes}")

    # Descartar filas de totales/resumen (no tienen Código Autorización)
    antes = len(df)
    df = df[df["Código Autorización"].notna()].copy()
    descartadas = antes - len(df)
    if descartadas:
        print(f"[OK] {descartadas} fila(s) de resumen descartadas (sin Código Autorización).")

    return df


# ---------------------------------------------------------------------------
# Transformación
# ---------------------------------------------------------------------------
def extraer_columnas(df: pd.DataFrame) -> pd.DataFrame:
    return df[list(COLUMNAS_REQUERIDAS.keys())].rename(columns=COLUMNAS_REQUERIDAS)


def normalizar(df: pd.DataFrame) -> pd.DataFrame:

    # --- payment_method: venta / devolucion ---
    df["payment_method"] = (
        df["payment_method"].str.strip().str.lower()
        .map({"venta": "venta", "devolución": "devolucion", "devolucion": "devolucion"})
        .fillna(df["payment_method"].str.strip().str.lower())
    )

    # --- payment_type: credito / debito ---
    df["payment_type"] = (
        df["payment_type"].str.strip().str.lower()
        .map({"crédito": "credito", "credito": "credito",
              "débito":  "debito",  "debito":  "debito"})
        .fillna(df["payment_type"].str.strip().str.lower())
    )

    # --- status: normalizar a minúsculas ---
    df["status"] = df["status"].str.strip().str.lower()

    # --- installments: 0 si tipo_plan es 0 (contado) ---
    df["installments"] = pd.to_numeric(df["installments"], errors="coerce").fillna(0).astype(int)
    df["tipo_plan"]    = pd.to_numeric(df["tipo_plan"],    errors="coerce").fillna(0).astype(int)
    df.loc[df["tipo_plan"] == 0, "installments"] = 0
    # tipo_plan queda como columna en la tabla, solo normalizado

    # --- fechas ---
    df["fecha_transaccion"] = pd.to_datetime(df["fecha_transaccion"], errors="coerce")
    df["fecha_deposito"]    = pd.to_datetime(df["fecha_deposito"],    errors="coerce", dayfirst=True)
    # Fecha Liberación puede venir como 01/01/0001 (vacía en Banorte) → dejar NULL
    df.loc[df["fecha_deposito"].dt.year < 1900, "fecha_deposito"] = None

    # --- monto: negativo en devoluciones, se mantiene así ---
    df["monto"] = pd.to_numeric(df["monto"], errors="coerce")

    # --- comision_banorte: normalizar a positivo ---
    df["comision_banorte_raw"] = pd.to_numeric(df["comision_banorte_raw"], errors="coerce").fillna(0)
    df["comision_banorte"]     = df["comision_banorte_raw"].abs()
    df = df.drop(columns=["comision_banorte_raw"])

    # --- monto_neto: lo que efectivamente recibe Battery Plus ---
    df["monto_neto"] = df["monto"].abs() - df["comision_banorte"]
    # En devoluciones el monto es negativo, monto_neto también debe serlo
    df.loc[df["payment_method"] == "devolucion", "monto_neto"] = df["monto_neto"] * -1

    # --- moneda: siempre MXP según Banorte ---
    df["moneda"] = df["moneda"].fillna("MXP")

    # --- codigo_autorizacion: string limpio ---
    df["codigo_autorizacion"] = df["codigo_autorizacion"].astype(str).str.strip()

    # --- fecha_carga: timestamp de esta ejecución ---
    df["fecha_carga"] = datetime.now()

    # --- periodo_inicio / periodo_fin: NULL por ahora (pendiente de definición) ---
    df["periodo_inicio"] = None
    df["periodo_fin"]    = None

    # IVA solo para validación interna — no va a la BD
    df["iva_pagos_diferidos"] = pd.to_numeric(df["iva_pagos_diferidos"], errors="coerce").fillna(0).abs()

    return df


# ---------------------------------------------------------------------------
# Validación
# ---------------------------------------------------------------------------
def validar_comisiones(df: pd.DataFrame) -> bool:
    print("\n--- Validaciones ---")
    ok_total = True

    mask_msi = df["installments"] > 0
    if mask_msi.any():
        # Reconstruir sobretasa implícita para validar IVA
        iva = pd.to_numeric(df.loc[mask_msi, "iva_pagos_diferidos"], errors="coerce")
        comision = df.loc[mask_msi, "comision_banorte"]
        ratio = (iva / comision.replace(0, float("nan"))).mean()
        ok = abs(ratio - 0.16) < 0.05
        ok_total &= ok
        estado = "✓" if ok else "⚠ REVISAR"
        print(f"  IVA/Comisión promedio (MSI): {ratio:.4f}  (esperado ~0.16) {estado}")
    else:
        print("  No hay filas con MSI para validar IVA.")

    print(f"  Filas totales  : {len(df)}")
    print(f"  Ventas         : {(df['payment_method'] == 'venta').sum()}")
    print(f"  Devoluciones   : {(df['payment_method'] == 'devolucion').sum()}")
    print(f"  Con MSI        : {mask_msi.sum()}")
    print(f"  Monto total    : ${df.loc[df['payment_method']=='venta','monto'].sum():,.2f}")
    print(f"  Comisión total : ${df['comision_banorte'].sum():,.2f}")
    print(f"  Neto total     : ${df.loc[df['payment_method']=='venta','monto_neto'].sum():,.2f}")

    return ok_total


# ---------------------------------------------------------------------------
# Carga a SQL Server
# ---------------------------------------------------------------------------
def conectar_db() -> pyodbc.Connection:
    conn_str = (
        f"DRIVER={{ODBC Driver 17 for SQL Server}};"
        f"SERVER={DB_SERVER};"
        f"DATABASE={DB_NAME};"
        f"UID={DB_USER};"
        f"PWD={DB_PASSWORD};"
        f"Encrypt=yes;TrustServerCertificate=yes;"
    )
    try:
        conn = pyodbc.connect(conn_str, timeout=10)
        print(f"[OK] Conectado a {DB_NAME} en {DB_SERVER}")
        return conn
    except Exception as e:
        sys.exit(f"[ERROR] No se pudo conectar a SQL Server: {e}")


def upsert_db(df: pd.DataFrame, conn: pyodbc.Connection) -> None:
    """
    UPSERT contra dbo.pagos_banorte usando MERGE por codigo_autorizacion.
    Si ya existe el registro → actualiza. Si no → inserta.
    """
    cursor = conn.cursor()

    # Columnas que van a la BD (excluye iva_pagos_diferidos que es solo para validación)
    cols_db = [
        "codigo_autorizacion", "afiliacion", "terminal", "fecha_transaccion",
        "fecha_deposito", "monto", "monto_neto", "comision_banorte", "moneda",
        "installments", "payment_type", "payment_method", "status", "tipo_plan",
        "fecha_carga", "periodo_inicio", "periodo_fin",
    ]

    # Columnas a actualizar en MATCHED: todo excepto codigo_autorizacion y fecha_carga
    # fecha_carga se maneja con GETDATE() separado para no duplicarla
    cols_update = [c for c in cols_db if c not in ("codigo_autorizacion", "fecha_carga")]

    merge_sql = f"""
    MERGE {DB_TABLE} AS target
    USING (VALUES ({','.join(['?'] * len(cols_db))}))
        AS source ({','.join(cols_db)})
    ON target.codigo_autorizacion = source.codigo_autorizacion
    WHEN MATCHED THEN UPDATE SET
        {', '.join(f'target.{c} = source.{c}' for c in cols_update)},
        target.fecha_carga = GETDATE()
    WHEN NOT MATCHED THEN INSERT
        ({','.join(cols_db)})
        VALUES ({','.join(['source.' + c for c in cols_db])});
    """

    insertados = 0
    actualizados = 0
    errores = 0

    for _, row in df[cols_db].iterrows():
        valores = []
        for c in cols_db:
            v = row[c]
            # Convertir NaT / NaN a None para SQL
            if pd.isna(v) if not isinstance(v, (list, dict)) else False:
                valores.append(None)
            else:
                valores.append(v)
        try:
            cursor.execute(merge_sql, valores)
            # rowcount 1 = insert, 2 = update en MERGE con SQL Server
            if cursor.rowcount == 1:
                insertados += 1
            else:
                actualizados += 1
        except Exception as e:
            errores += 1
            print(f"  [⚠] Error en cod_autorizacion={row['codigo_autorizacion']}: {e}")

    conn.commit()
    cursor.close()
    print(f"  Insertados : {insertados}")
    print(f"  Actualizados: {actualizados}")
    if errores:
        print(f"  ⚠ Errores  : {errores}")


# ---------------------------------------------------------------------------
# CSV (opcional)
# ---------------------------------------------------------------------------
def guardar_csv(df: pd.DataFrame, ruta: str) -> None:
    cols_csv = [
        "codigo_autorizacion", "afiliacion", "terminal", "payment_method",
        "fecha_transaccion", "fecha_deposito", "monto", "monto_neto",
        "comision_banorte", "moneda", "installments", "payment_type",
        "status", "tipo_plan",
    ]
    df[cols_csv].to_csv(ruta, index=False, encoding="utf-8-sig")
    print(f"[OK] CSV guardado: {ruta}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="ETL Banorte → SQL Server (MercadoPagoSync)")
    parser.add_argument(
        "input", nargs="?", default=None,
        help=f"Ruta al .xlsx de Banorte. Si se omite, usa el .xlsx más reciente en {CARPETA_DEFAULT}"
    )
    parser.add_argument("--output",    default=os.path.join(CARPETA_DEFAULT, "banorte_limpio.csv"))
    parser.add_argument("--solo-csv",  action="store_true", help="Solo genera CSV, no carga a BD")
    parser.add_argument("--solo-db",   action="store_true", help="Solo carga a BD, no genera CSV")
    args = parser.parse_args()

    if args.input is None:
        args.input = buscar_excel_default(CARPETA_DEFAULT)

    print(f"\n[1/5] Leyendo: {args.input}")
    df_raw = leer_reporte(args.input)
    print(f"      {len(df_raw)} filas, {len(df_raw.columns)} columnas en el reporte original.")

    print("[2/5] Extrayendo columnas...")
    df = extraer_columnas(df_raw)

    print("[3/5] Normalizando...")
    df = normalizar(df)

    print("[4/5] Validando comisiones...")
    validar_comisiones(df)

    print("[5/5] Cargando resultado...")

    if not args.solo_db:
        guardar_csv(df, args.output)

    if not args.solo_csv:
        conn = conectar_db()
        upsert_db(df, conn)
        conn.close()

    print("\n[LISTO] ETL Banorte completado.")


if __name__ == "__main__":
    main()