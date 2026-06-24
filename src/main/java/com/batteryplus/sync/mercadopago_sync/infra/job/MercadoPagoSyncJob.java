package com.batteryplus.sync.mercadopago_sync.infra.job;

import com.batteryplus.sync.mercadopago_sync.adapter.mercadopago.MercadoPagoClient;
import com.batteryplus.sync.mercadopago_sync.core.model.MercadoPagoPayment;
import com.batteryplus.sync.mercadopago_sync.infra.repository.ComisionesRepository;
import com.batteryplus.sync.mercadopago_sync.infra.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Component
public class MercadoPagoSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoSyncJob.class);

    private final MercadoPagoClient client;
    private final PaymentRepository repository;

    @Autowired(required = false)
    private ComisionesRepository comisionesRepository;

    @Value("${mercadopago.sync.days-back:3}")
    private int daysBack;

    public MercadoPagoSyncJob(MercadoPagoClient client, PaymentRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void sync() {
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime globalFrom;

        if (daysBack > 3) {
            globalFrom = to.minusDays(daysBack);
            log.info("Recarga histórica forzada: {} días atrás desde {}", daysBack, globalFrom);
        } else {
            globalFrom = repository.getMaxFechaCreacion()
                    .map(last -> last.minusHours(1))
                    .orElse(to.minusDays(daysBack));
        }

        // 1. Sync en ventanas de 7 días para evitar el bug de paginación de MP
        int totalGuardados = 0;
        OffsetDateTime windowStart = globalFrom;

        while (windowStart.isBefore(to)) {
            OffsetDateTime windowEnd = windowStart.plusDays(7);
            if (windowEnd.isAfter(to)) windowEnd = to;

            log.info("Sincronizando ventana: {} -> {}", windowStart, windowEnd);
            List<MercadoPagoPayment> payments = client.fetchPayments(windowStart, windowEnd);
            log.info("Pagos en ventana: {}", payments.size());
            repository.upsertAll(payments);
            totalGuardados += payments.size();

            windowStart = windowEnd;
        }

        log.info("Sync MP completado: {} pagos totales", totalGuardados);

        // 2. Sync comisiones
        if (comisionesRepository != null) {
            String fechaDesde = LocalDate.now().minusDays(daysBack).toString();
            String fechaHasta = LocalDate.now().toString();
            log.info("Sincronizando comisiones: {} -> {}", fechaDesde, fechaHasta);
            comisionesRepository.syncComisiones(fechaDesde, fechaHasta);
            log.info("Sync comisiones completado");
        }
    }
}