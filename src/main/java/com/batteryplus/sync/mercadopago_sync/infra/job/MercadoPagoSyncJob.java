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
        OffsetDateTime from = to.minusDays(daysBack);

        // 1. Sync MercadoPago API → MercadoPagoSync
        log.info("Iniciando sync MercadoPago: {} -> {}", from, to);
        List<MercadoPagoPayment> payments = client.fetchPayments(from, to);
        log.info("Pagos encontrados: {}", payments.size());
        repository.upsertAll(payments);
        log.info("Sync MP completado: {} pagos", payments.size());

        // 2. Sync comisiones BatteryPlus → MercadoPagoSync
        if (comisionesRepository != null) {
            String fechaDesde = LocalDate.now().minusDays(daysBack).toString();
            String fechaHasta = LocalDate.now().toString();
            log.info("Sincronizando comisiones: {} -> {}", fechaDesde, fechaHasta);
            comisionesRepository.syncComisiones(fechaDesde, fechaHasta);
            log.info("Sync comisiones completado");
        }
    }
}