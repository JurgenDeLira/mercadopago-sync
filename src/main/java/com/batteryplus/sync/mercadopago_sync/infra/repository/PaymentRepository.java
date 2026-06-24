package com.batteryplus.sync.mercadopago_sync.infra.repository;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "app.datasource.mercadopago.enabled", havingValue = "true")
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(@Qualifier("mercadopagoJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<com.batteryplus.sync.mercadopago_sync.core.model.MercadoPagoPayment> payments) {
        for (com.batteryplus.sync.mercadopago_sync.core.model.MercadoPagoPayment p : payments) {
            upsert(p);
        }
    }

    public Optional<OffsetDateTime> getMaxFechaCreacion() {
        String sql = "SELECT MAX(fecha_creacion) FROM pagos_mercadopago";
        java.sql.Timestamp result = jdbc.queryForObject(sql, java.sql.Timestamp.class);
        if (result == null) return Optional.empty();
        return Optional.of(result.toLocalDateTime()
                .atOffset(java.time.ZoneOffset.ofHours(-7)));
    }

    private void upsert(com.batteryplus.sync.mercadopago_sync.core.model.MercadoPagoPayment p) {
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
                // USING
                p.id(),
                // WHEN MATCHED
                p.status(), p.statusDetail(),
                // WHEN NOT MATCHED
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