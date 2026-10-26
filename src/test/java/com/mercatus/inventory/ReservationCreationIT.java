package com.mercatus.inventory;

import com.mercatus.inventory.support.ConcurrentRequests;
import com.mercatus.inventory.support.InventoryFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.reservation.expiry-interval-ms=3600000")
class ReservationCreationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    InventoryFixture inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryFixture(port, jdbc);
    }

    @Test
    void duplicateCreationReturnsOneReservationAndTerminalRepeatKeepsOriginalLines() throws Exception {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 10);
        UUID order = UUID.randomUUID();
        List<Callable<String>> calls = IntStream.range(0, 20)
                .mapToObj(i -> (Callable<String>) () -> inventory.reserve(order, sku, 2)).toList();
        List<String> ids = ConcurrentRequests.run(calls);
        assertThat(ids).hasSize(20).containsOnly(ids.getFirst());
        assertThat(inventory.stock(sku).reserved()).isEqualTo(2);
        String id = ids.getFirst();
        assertThat(inventory.transition(id, "release")).isEqualTo(204);
        assertThat(inventory.reserve(order, "MISSING-" + UUID.randomUUID(), 8)).isEqualTo(id);
        assertThat(inventory.stock(sku).reserved()).isZero();
        assertThat(jdbc.queryForObject("select quantity from reservation_line where reservation_id = ?",
                Long.class, UUID.fromString(id))).isEqualTo(2);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void failedCreationRollsBackClaimAndAllowsSameOrderToRetry() {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 1);
        UUID order = UUID.randomUUID();
        int status = inventory.client().post().uri("/reservations").body(Map.of("orderId", order,
                        "lines", List.of(Map.of("skuCode", sku, "quantity", 2))))
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(422);
        assertThat(jdbc.queryForObject("select count(*) from reservation where order_id = ?", Long.class, order))
                .isZero();
        inventory.receive(sku, 1);
        assertThat(inventory.reserve(order, sku, 2)).isNotBlank();
        assertThat(inventory.stock(sku).reserved()).isEqualTo(2);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void nullReservationLineReturnsValidationProblem() {
        inventory.client().post().uri("/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"orderId\":\"" + UUID.randomUUID() + "\",\"lines\":[null]}")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(400);
                    assertThat(response.getHeaders().getContentType().toString())
                            .startsWith("application/problem+json");
                    return null;
                });
    }
}
