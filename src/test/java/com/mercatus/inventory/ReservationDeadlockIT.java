package com.mercatus.inventory;

import com.mercatus.inventory.support.ConcurrentRequests;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationDeadlockIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Test
    void concurrentReservationsWithOppositeSkuOrderDoNotDeadlock() throws Exception {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(45000);
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory).build();
        String a = "A-" + UUID.randomUUID();
        String b = "B-" + UUID.randomUUID();
        for (String sku : List.of(a, b)) {
            client.post().uri("/stock/" + sku + "/receipts").body(Map.of("quantity", 1000))
                    .retrieve().toBodilessEntity();
        }
        List<Callable<Integer>> calls = IntStream.range(0, 200).mapToObj(i -> (Callable<Integer>) () ->
                client.post().uri("/reservations")
                        .body(Map.of("orderId", UUID.randomUUID(), "lines", List.of(
                                Map.of("skuCode", i % 2 == 0 ? a : b, "quantity", 1),
                                Map.of("skuCode", i % 2 == 0 ? b : a, "quantity", 1))))
                        .exchange((req, res) -> res.getStatusCode().value())).toList();
        assertThat(ConcurrentRequests.run(calls)).hasSize(200).containsOnly(201);
        for (String sku : List.of(a, b)) {
            Map<?, ?> stock = client.get().uri("/stock/" + sku).retrieve().body(Map.class);
            assertThat(((Number) stock.get("reserved")).longValue()).isEqualTo(200);
            assertThat(((Number) stock.get("available")).longValue()).isEqualTo(800);
        }
    }
}
