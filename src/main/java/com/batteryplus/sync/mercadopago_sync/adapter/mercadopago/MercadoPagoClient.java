package com.batteryplus.sync.mercadopago_sync.adapter.mercadopago;

import com.batteryplus.sync.mercadopago_sync.core.model.MercadoPagoPayment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class MercadoPagoClient {

    private static final String BASE_URL = "https://api.mercadopago.com/v1/payments/search";
    private static final DateTimeFormatter FMT_API = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    @Value("${mercadopago.access-token}")
    private String accessToken;

    @Value("${mercadopago.sync.page-size:100}")
    private int pageSize;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper;

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoClient.class);


    public MercadoPagoClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<MercadoPagoPayment> fetchPayments(OffsetDateTime from, OffsetDateTime to) {
        List<MercadoPagoPayment> all = new ArrayList<>();
        int offset = 0;

        while (true) {
            String url = BASE_URL
                    + "?begin_date=" + from.format(FMT_API)
                    + "&end_date=" + to.format(FMT_API)
                    + "&range=date_created"
                    + "&sort=date_created&criteria=asc"
                    + "&limit=" + pageSize
                    + "&offset=" + offset;

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                // Log para ver qué devuelve la API
                log.info("MP API response status: {}", response.statusCode());
                log.info("MP API response body: {}", response.body());

                JsonNode root = mapper.readTree(response.body());

                // Verificar que tiene el formato esperado
                JsonNode paging = root.get("paging");
                JsonNode results = root.get("results");

                if (paging == null || results == null) {
                    log.error("Respuesta inesperada de MP: {}", response.body());
                    break;
                }

                int total = paging.get("total").asInt();
                log.info("Total pagos encontrados: {}", total);

                if (results.isEmpty()) break;

                for (JsonNode r : results) {
                    all.add(map(r));
                }

                offset += pageSize;
                if (offset >= total) break;

            } catch (Exception e) {
                throw new RuntimeException("Error llamando API MercadoPago: " + e.getMessage(), e);
            }
        }

        return all;
    }
    private MercadoPagoPayment map(JsonNode r) {
        return new MercadoPagoPayment(
                r.get("id").asLong(),
                getText(r, "store_id"),
                getText(r, "pos_id"),
                getSerialTerminal(r),
                parseDate(r, "date_created"),
                parseDate(r, "date_approved"),
                getBigDecimal(r, "transaction_amount"),
                getNetAmount(r),
                getComision(r),
                getText(r, "currency_id"),
                r.has("installments") ? r.get("installments").asInt() : 1,
                getText(r, "payment_type_id"),
                getText(r, "payment_method_id"),
                getText(r, "status"),
                getText(r, "status_detail"),
                getText(r, "external_reference"),
                getText(r, "description"),
                getText(r, "operation_type")
        );
    }

    private String getText(JsonNode r, String field) {
        JsonNode n = r.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private OffsetDateTime parseDate(JsonNode r, String field) {
        String val = getText(r, field);
        if (val == null) return null;
        try {
            return OffsetDateTime.parse(val, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return OffsetDateTime.parse(val, FMT_API);
        }
    }

    private BigDecimal getBigDecimal(JsonNode r, String field) {
        JsonNode n = r.get(field);
        return (n == null || n.isNull()) ? BigDecimal.ZERO : n.decimalValue();
    }

    private BigDecimal getNetAmount(JsonNode r) {
        JsonNode td = r.get("transaction_details");
        if (td == null) return BigDecimal.ZERO;
        JsonNode n = td.get("net_received_amount");
        return (n == null || n.isNull()) ? BigDecimal.ZERO : n.decimalValue();
    }

    private BigDecimal getComision(JsonNode r) {
        JsonNode fees = r.get("fee_details");
        if (fees == null || !fees.isArray()) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode fee : fees) {
            JsonNode amt = fee.get("amount");
            if (amt != null && !amt.isNull()) {
                total = total.add(amt.decimalValue());
            }
        }
        return total;
    }

    private String getSerialTerminal(JsonNode r) {
        try {
            return r.get("point_of_interaction")
                    .get("device")
                    .get("serial_number").asText();
        } catch (Exception e) {
            return null;
        }
    }
}