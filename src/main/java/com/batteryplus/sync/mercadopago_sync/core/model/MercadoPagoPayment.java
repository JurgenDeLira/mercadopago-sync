package com.batteryplus.sync.mercadopago_sync.core.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MercadoPagoPayment(
        Long id,
        String storeId,
        String posId,
        String serialTerminal,
        OffsetDateTime fechaCreacion,
        OffsetDateTime fechaAprobacion,
        BigDecimal monto,
        BigDecimal montoNeto,
        BigDecimal comisionMp,
        String moneda,
        Integer installments,
        String paymentType,
        String paymentMethod,
        String status,
        String statusDetail,
        String externalReference,
        String description,
        String operationType
) {}