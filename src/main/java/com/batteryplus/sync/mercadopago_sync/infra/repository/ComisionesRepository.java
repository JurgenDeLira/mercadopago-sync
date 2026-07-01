package com.batteryplus.sync.mercadopago_sync.infra.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "app.datasource.batteryplus.enabled", havingValue = "true")
public class ComisionesRepository {

    private final JdbcTemplate jdbcBatteryPlus;
    private final JdbcTemplate jdbcMercadoPago;

    public ComisionesRepository(
            @Qualifier("batteryplusJdbcTemplate") JdbcTemplate jdbcBatteryPlus,
            @Qualifier("mercadopagoJdbcTemplate") JdbcTemplate jdbcMercadoPago) {
        this.jdbcBatteryPlus = jdbcBatteryPlus;
        this.jdbcMercadoPago = jdbcMercadoPago;
    }

    public void syncComisiones(String fechaDesde, String fechaHasta) {

        // 1. Lee comisiones de BatteryPlus (Azure SQL)
        // IMPORTANTE: Azure SQL no soporta referencias cross-database.
        // La comision Banorte se enriquece en Java desde jdbcMercadoPago.
        String queryComisiones = """
            DECLARE @a DATE = ?;
            DECLARE @b DATE = ?;
            """ + COMISIONES_SQL;

        List<Map<String, Object>> rows = jdbcBatteryPlus.queryForList(
                queryComisiones, fechaDesde, fechaHasta
        );

        if (rows.isEmpty()) return;

        // 2. Crea tabla en MercadoPagoSync si no existe
        jdbcMercadoPago.execute(CREATE_TABLE_SQL);

        // 3. Upsert cada fila
        for (Map<String, Object> row : rows) {

            // Buscar comision Banorte real desde pagos_banorte usando el campo "referencia"
            // que es el numero de 12 digitos que aparece en el ticket fisico de la terminal
            // y que el cajero captura en Verina en el campo cm.Referencia cuando banco = Banorte
            Object referenciaBanorte = row.get("Referencia_Banorte");
            double comBanorte = 0.0;

            if (referenciaBanorte != null && !referenciaBanorte.toString().isBlank()) {
                List<Map<String, Object>> pbRows = jdbcMercadoPago.queryForList(
                        "SELECT comision_banorte FROM dbo.pagos_banorte " +
                                "WHERE referencia = ? AND payment_method = 'venta'",
                        referenciaBanorte.toString()
                );
                if (!pbRows.isEmpty() && pbRows.get(0).get("comision_banorte") != null) {
                    comBanorte = toDouble(pbRows.get(0).get("comision_banorte"));
                }
            }

            // Recalcular Total_Comisiones incluyendo Banorte
            double tarjetaCredito = toDouble(row.get("Tarjeta_Credito"));
            double tarjetaDebito  = toDouble(row.get("Tarjeta_Debito"));
            double mercadoPago    = toDouble(row.get("MercadoPago"));
            int    msiMp          = toInt(row.get("MSI_MP"));

            double tasaMp = switch (msiMp) {
                case 3  -> 0.053128;
                case 6  -> 0.076328;
                case 9  -> 0.111128;
                case 12 -> 0.137808;
                case 18 -> 0.195808;
                case 24 -> 0.267728;
                default -> 0.010208;
            };

            double totalComisiones = Math.round((
                    tarjetaCredito * 0.010208 +
                            tarjetaDebito  * 0.00986  +
                            mercadoPago    * tasaMp   +
                            comBanorte
            ) * 100.0) / 100.0;

            jdbcMercadoPago.update(UPSERT_SQL,
                    row.get("Ticket"),
                    row.get("IDSucursal"),
                    // INSERT values
                    row.get("Ticket"),
                    row.get("IDSucursal"),
                    row.get("Sucursal"),
                    row.get("Fecha"),
                    row.get("Tipo"),
                    row.get("Total_Cobrado"),
                    row.get("Efectivo"),
                    row.get("Tarjeta_Credito"),
                    row.get("MSI_Credito"),
                    row.get("Terminal_Credito"),
                    row.get("Comision_Credito"),
                    row.get("Tarjeta_Debito"),
                    row.get("Terminal_Debito"),
                    row.get("Comision_Debito"),
                    row.get("MercadoPago"),
                    row.get("MSI_MP"),
                    row.get("Referencia_MP"),
                    row.get("Comision_MP"),
                    row.get("Transferencia"),
                    row.get("Cheque"),
                    totalComisiones,
                    row.get("Tarjeta_Banorte"),
                    referenciaBanorte,
                    comBanorte,
                    // UPDATE values
                    row.get("Total_Cobrado"),
                    row.get("Efectivo"),
                    row.get("Tarjeta_Credito"),
                    row.get("Tarjeta_Debito"),
                    row.get("MercadoPago"),
                    row.get("Transferencia"),
                    row.get("Cheque"),
                    totalComisiones,
                    row.get("Tarjeta_Banorte"),
                    referenciaBanorte,
                    comBanorte
            );
        }
    }

    // ── Helpers ──────────────────────────────────────────
    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0.0; }
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    // ── DDL ──────────────────────────────────────────────
    private static final String CREATE_TABLE_SQL = """
        IF NOT EXISTS (
            SELECT 1 FROM sys.tables WHERE name = 'comisiones_verina'
        )
        CREATE TABLE dbo.comisiones_verina (
            Ticket               INT,
            IDSucursal           INT,
            Sucursal             NVARCHAR(100),
            Fecha                NVARCHAR(20),
            Tipo                 NVARCHAR(50),
            Total_Cobrado        DECIMAL(18,2),
            Efectivo             DECIMAL(18,2),
            Tarjeta_Credito      DECIMAL(18,2),
            MSI_Credito          INT,
            Terminal_Credito     NVARCHAR(50),
            Comision_Credito     DECIMAL(18,2),
            Tarjeta_Debito       DECIMAL(18,2),
            Terminal_Debito      NVARCHAR(50),
            Comision_Debito      DECIMAL(18,2),
            MercadoPago          DECIMAL(18,2),
            MSI_MP               INT,
            Referencia_MP        NVARCHAR(100),
            Comision_MP          DECIMAL(18,2),
            Transferencia        DECIMAL(18,2),
            Cheque               DECIMAL(18,2),
            Total_Comisiones     DECIMAL(18,2),
            fecha_sincronizacion DATETIME DEFAULT GETDATE(),
            Tarjeta_Banorte      DECIMAL(18,2),
            Referencia_Banorte   NVARCHAR(100),
            Comision_Banorte     DECIMAL(18,2),
            PRIMARY KEY (Ticket, IDSucursal)
        );
        """;

    // ── UPSERT ───────────────────────────────────────────
    private static final String UPSERT_SQL = """
        MERGE dbo.comisiones_verina AS target
        USING (SELECT ? AS Ticket, ? AS IDSucursal) AS source
            ON target.Ticket = source.Ticket
           AND target.IDSucursal = source.IDSucursal
        WHEN NOT MATCHED THEN INSERT (
            Ticket, IDSucursal, Sucursal, Fecha, Tipo,
            Total_Cobrado, Efectivo,
            Tarjeta_Credito, MSI_Credito, Terminal_Credito, Comision_Credito,
            Tarjeta_Debito, Terminal_Debito, Comision_Debito,
            MercadoPago, MSI_MP, Referencia_MP, Comision_MP,
            Transferencia, Cheque, Total_Comisiones,
            Tarjeta_Banorte, Referencia_Banorte, Comision_Banorte
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        WHEN MATCHED THEN UPDATE SET
            Total_Cobrado        = ?,
            Efectivo             = ?,
            Tarjeta_Credito      = ?,
            Tarjeta_Debito       = ?,
            MercadoPago          = ?,
            Transferencia        = ?,
            Cheque               = ?,
            Total_Comisiones     = ?,
            Tarjeta_Banorte      = ?,
            Referencia_Banorte   = ?,
            Comision_Banorte     = ?,
            fecha_sincronizacion = GETDATE();
        """;

    // ── QUERY COMISIONES ─────────────────────────────────
    // Corre contra Azure SQL (BatteryPlus) — sin cross-db references.
    // Referencia_Banorte = el numero de 12 digitos del ticket fisico de Banorte
    // que el cajero captura en Verina en cm.Referencia cuando banco = 'Banorte'
    private static final String COMISIONES_SQL = """
        WITH Pagos AS (
            SELECT
                cm.IDTicket, cm.IDSucursal,
                ROUND(SUM(CASE WHEN cm.IDFormaPago != 4 THEN cm.Monto ELSE 0 END),2) AS Total_Cobrado,
                ROUND(SUM(CASE WHEN cm.IDFormaPago = 1 THEN cm.Monto ELSE 0 END),2) AS Efectivo,
                ROUND(SUM(CASE WHEN cm.IDFormaPago = 2 AND ISNULL(b.Nombre,'') NOT LIKE '%MERCADO%' AND ISNULL(b.Nombre,'') NOT LIKE '%BANORTE%' THEN cm.Monto ELSE 0 END),2) AS Tarjeta_Credito,
                ROUND(SUM(CASE WHEN cm.IDFormaPago = 8 AND ISNULL(b.Nombre,'') NOT LIKE '%MERCADO%' AND ISNULL(b.Nombre,'') NOT LIKE '%BANORTE%' THEN cm.Monto ELSE 0 END),2) AS Tarjeta_Debito,
                ROUND(SUM(CASE WHEN b.Nombre LIKE '%MERCADO%' THEN cm.Monto ELSE 0 END),2) AS MercadoPago,
                ROUND(SUM(CASE WHEN cm.IDFormaPago = 3 THEN cm.Monto ELSE 0 END),2) AS Transferencia,
                ROUND(SUM(CASE WHEN cm.IDFormaPago = 7 THEN cm.Monto ELSE 0 END),2) AS Cheque,
                MAX(CASE WHEN cm.IDFormaPago = 2 AND ISNULL(b.Nombre,'') NOT LIKE '%MERCADO%' AND ISNULL(b.Nombre,'') NOT LIKE '%BANORTE%' THEN cm.NoMesesCredito ELSE 0 END) AS MSI_Credito,
                MAX(CASE WHEN b.Nombre LIKE '%MERCADO%' THEN cm.NoMesesCredito ELSE 0 END) AS MSI_MP,
                MAX(CASE WHEN cm.IDFormaPago = 2 AND ISNULL(b.Nombre,'') NOT LIKE '%MERCADO%' AND ISNULL(b.Nombre,'') NOT LIKE '%BANORTE%' THEN cm.Afiliacion ELSE NULL END) AS Terminal_Credito,
                MAX(CASE WHEN cm.IDFormaPago = 8 AND ISNULL(b.Nombre,'') NOT LIKE '%MERCADO%' AND ISNULL(b.Nombre,'') NOT LIKE '%BANORTE%' THEN cm.Afiliacion ELSE NULL END) AS Terminal_Debito,
                MAX(CASE WHEN b.Nombre LIKE '%MERCADO%' THEN cm.Referencia ELSE NULL END) AS Referencia_MP,
                MAX(CASE WHEN b.Nombre LIKE '%BANORTE%' THEN cm.Referencia ELSE NULL END) AS Referencia_Banorte,
                ROUND(SUM(CASE WHEN b.Nombre LIKE '%BANORTE%' THEN cm.Monto ELSE 0 END),2) AS Tarjeta_Banorte
            FROM [Cajas/Movimientos] cm
            LEFT JOIN Bancos b ON b.IDBanco = cm.Banco
            WHERE cm.IDTicket IS NOT NULL
              AND cm.IDFormaPago != 4
              AND cm.Fecha BETWEEN @a AND @b
            GROUP BY cm.IDTicket, cm.IDSucursal
        ),
        Ticket_Info AS (
            SELECT DISTINCT vt.Numero, vt.IDSucursal, vt.Sucursal,
                FORMAT(vt.Fecha,'dd/MM/yyyy') AS Fecha
            FROM vTickets vt
        )
        SELECT
            p.IDTicket AS Ticket, p.IDSucursal,
            ti.Sucursal, ti.Fecha,
            CASE
                WHEN vc.Numero IS NOT NULL THEN 'Cancelacion'
                WHEN vd.Numero IS NOT NULL THEN 'Devolucion'
                WHEN vg.Numero IS NOT NULL THEN 'Garantia'
                ELSE 'Venta Normal'
            END AS Tipo,
            p.Total_Cobrado, p.Efectivo,
            p.Tarjeta_Credito, p.MSI_Credito, p.Terminal_Credito,
            ROUND(p.Tarjeta_Credito * 0.010208, 2) AS Comision_Credito,
            p.Tarjeta_Debito, p.Terminal_Debito,
            ROUND(p.Tarjeta_Debito * 0.00986, 2) AS Comision_Debito,
            p.MercadoPago, p.MSI_MP, p.Referencia_MP,
            ROUND(p.MercadoPago * CASE p.MSI_MP
                WHEN 3  THEN 0.053128 WHEN 6  THEN 0.076328
                WHEN 9  THEN 0.111128 WHEN 12 THEN 0.137808
                WHEN 18 THEN 0.195808 WHEN 24 THEN 0.267728
                ELSE 0.010208 END, 2) AS Comision_MP,
            p.Transferencia, p.Cheque,
            p.Tarjeta_Banorte,
            p.Referencia_Banorte
        FROM Pagos p
        LEFT JOIN Ticket_Info ti ON p.IDTicket = ti.Numero AND p.IDSucursal = ti.IDSucursal
        LEFT JOIN (SELECT DISTINCT Numero, IDSucursal FROM [vTickets/Cancelaciones]) vc ON p.IDTicket = vc.Numero AND p.IDSucursal = vc.IDSucursal
        LEFT JOIN (SELECT DISTINCT Numero, IDSucursal FROM [vTickets/Devoluciones])  vd ON p.IDTicket = vd.Numero AND p.IDSucursal = vd.IDSucursal
        LEFT JOIN (SELECT DISTINCT Numero, IDSucursal FROM [vTickets/Garantias])     vg ON p.IDTicket = vg.Numero AND p.IDSucursal = vg.IDSucursal
        ORDER BY ti.Fecha, p.IDSucursal, p.IDTicket
        """;
}