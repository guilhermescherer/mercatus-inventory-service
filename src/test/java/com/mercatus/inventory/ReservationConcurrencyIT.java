package com.mercatus.inventory;

import com.mercatus.inventory.support.ConcurrentRequests;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ProblemDetail;
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
class ReservationConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Test
    void lastUnitIsReservedByExactlyOneConcurrentRequest() throws Exception {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(45000);
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory).build();
        String sku = "SKU-" + UUID.randomUUID();
        client.post().uri("/stock/" + sku + "/receipts").body(Map.of("quantity", 1))
                .retrieve().toBodilessEntity();

        List<Callable<Integer>> calls = IntStream.range(0, 100).mapToObj(i -> (Callable<Integer>) () ->
                client.post().uri("/reservations")
                        .body(Map.of("orderId", UUID.randomUUID(),
                                "lines", List.of(Map.of("skuCode", sku, "quantity", 1))))
                        .exchange((req, res) -> {
                            int status = res.getStatusCode().value();
                            if (status == 422) {
                                assertThat(res.bodyTo(ProblemDetail.class).getDetail())
                                        .isEqualTo("Insufficient stock for SKU " + sku);
                            }
                            return status;
                        })).toList();
        List<Integer> statuses = ConcurrentRequests.run(calls);
        assertThat(statuses).hasSize(100).containsOnly(201, 422);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(99);

        Map<?, ?> stock = client.get().uri("/stock/" + sku).retrieve().body(Map.class);
        assertThat(((Number) stock.get("available")).longValue()).isZero();
        assertThat(((Number) stock.get("reserved")).longValue()).isEqualTo(1);
    }
}
