package com.mercatus.inventory;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.reservation.expiry-interval-ms=3600000")
class StockAdjustmentIT {

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
    void adjustmentsValidateInputAndNeverChangeReservedOrCommittedStock() {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 10);
        inventory.reserve(UUID.randomUUID(), sku, 8);
        assertThat(inventory.adjust(sku, Map.of("delta", -3, "reason", "Damaged"))).isEqualTo(422);
        assertThat(inventory.adjust(sku, Map.of("delta", 0, "reason", "Count"))).isEqualTo(400);
        assertThat(inventory.adjust(sku, Map.of("delta", 2, "reason", " "))).isEqualTo(400);
        assertThat(inventory.adjust(sku, Map.of("reason", "Count"))).isEqualTo(400);
        assertThat(inventory.adjust(sku, Map.of("delta", "not-a-number", "reason", "Count"))).isEqualTo(400);
        assertThat(inventory.adjust(inventory.uniqueSkuCode(), Map.of("delta", 1, "reason", "Count"))).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where sku_code = ?",
                Long.class, sku)).isEqualTo(1);
        assertThat(inventory.adjust(sku, Map.of("delta", -2, "reason", "Damaged"))).isEqualTo(204);
        assertThat(inventory.adjust(sku, Map.of("delta", 3, "reason", "Count correction"))).isEqualTo(204);
        assertThat(inventory.stock(sku).onHand()).isEqualTo(11);
        assertThat(inventory.stock(sku).reserved()).isEqualTo(8);
        assertThat(jdbc.queryForList("select quantity from stock_movement where sku_code = ? and type = 'ADJUSTMENT'",
                Long.class, sku)).containsExactlyInAnyOrder(-2L, 3L);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void openApiDocumentsAdjustmentAndInvalidInputReturnsProblemDetails() {
        Map<?, ?> api = inventory.client().get().uri("/v3/api-docs").retrieve().body(Map.class);
        assertThat(((Map<?, ?>) api.get("paths")).containsKey("/stock/{skuCode}/adjustments")).isTrue();
        inventory.client().post().uri("/stock/" + inventory.uniqueSkuCode() + "/adjustments").body(Map.of("delta", 0, "reason", "Count"))
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(400);
                    assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
                    return null;
                });
    }

}
