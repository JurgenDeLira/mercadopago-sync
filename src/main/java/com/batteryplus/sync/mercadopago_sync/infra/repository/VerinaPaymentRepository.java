package com.batteryplus.sync.mercadopago_sync.infra.repository;

import com.batteryplus.sync.mercadopago_sync.core.model.MercadoPagoPayment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "app.datasource.verina.enabled", havingValue = "true")
public class VerinaPaymentRepository {

    private final JdbcTemplate jdbc;

    public VerinaPaymentRepository(@Qualifier("verinaJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<MercadoPagoPayment> payments) {
        for (MercadoPagoPayment p : payments) {
            upsert(p);
        }
    }

    private void upsert(MercadoPagoPayment p) {
        String sql = """
            MERGE dbo.pagos_mercadopago AS target
            USING (SELECT ? AS id) AS source ON target.id = source.id
            WHEN MATCHED THEN UPDATE SET
                status = ?, status_detail = ?, fecha_sincronizacion = GETDATE()
            WHEN NOT MATCHED THEN INSERT (
                id, store_id, pos_id, serial_terminal,
                fecha_creacion, fecha_aprobacion,
                monto, monto_neto, comision_mp, moneda,
                installments, payment_type, payment_method,
                status, status_detail,
                external_reference, description, operation_type
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);
            """;

        jdbc.update(sql,
                p.id(),
                p.status(), p.statusDetail(),
                p.id(), p.storeId(), p.posId(), p.serialTerminal(),
                toTimestamp(p.fechaCreacion()), toTimestamp(p.fechaAprobacion()),
                p.monto(), p.montoNeto(), p.comisionMp(), p.moneda(),
                p.installments(), p.paymentType(), p.paymentMethod(),
                p.status(), p.statusDetail(),
                p.externalReference(), p.description(), p.operationType()
        );
    }

    private Timestamp toTimestamp(java.time.OffsetDateTime odt) {
        return odt == null ? null : Timestamp.valueOf(odt.toLocalDateTime());
    }
}