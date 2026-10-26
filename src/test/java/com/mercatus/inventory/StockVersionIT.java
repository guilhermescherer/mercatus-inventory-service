package com.mercatus.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockVersionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private RestClient client() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        return RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory).build();
    }

    private long version(String sku) {
        return jdbc.queryForObject("select version from stock_item where sku_code = ?", Long.class, sku);
    }

    @Test
    void reservationIncrementsStockVersionAfterReceipt() {
        RestClient client = client();
        String sku = "V-" + UUID.randomUUID().toString().substring(0, 8);

        client.post().uri("/stock/" + sku + "/receipts").body(Map.of("quantity", 10))
                .retrieve().toBodilessEntity();
        long afterReceipt = version(sku);

        client.post().uri("/reservations")
                .body(Map.of("orderId", UUID.randomUUID().toString(),
                        "lines", List.of(Map.of("skuCode", sku, "quantity", 2))))
                .retrieve().toBodilessEntity();
        long afterReserve = version(sku);

        assertThat(afterReserve)
                .as("version increments after a second write (reserve) following the receipt")
                .isGreaterThan(afterReceipt);
    }
}
