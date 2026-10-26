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
class StockMovementIntegrityIT {

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
    void firstReceiptsAndMixedWritesPreserveEveryMovement() throws Exception {
        String sku = inventory.uniqueSkuCode();
        List<Callable<Integer>> receipts = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<Integer>) () -> {
                    inventory.receive(sku, 2);
                    return 1;
                }).toList();
        assertThat(ConcurrentRequests.run(receipts)).hasSize(10);
        assertThat(inventory.stock(sku).onHand()).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where sku_code = ?",
                Long.class, sku)).isEqualTo(10);
        List<Integer> results = ConcurrentRequests.run(List.of(
                () -> inventory.adjust(sku, Map.of("delta", -3, "reason", "Damage")),
                () -> { inventory.receive(sku, 4); return 202; },
                () -> { inventory.reserve(UUID.randomUUID(), sku, 5); return 201; }));
        assertThat(results).containsExactly(204, 202, 201);
        assertThat(inventory.stock(sku).onHand()).isEqualTo(21);
        assertThat(inventory.stock(sku).reserved()).isEqualTo(5);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void shipmentInsertFailureRollsBackAllSkuBalancesAndReservationState() {
        String a = "A-" + UUID.randomUUID();
        String b = "B-" + UUID.randomUUID();
        inventory.receive(a, 10);
        inventory.receive(b, 10);
        Map<?, ?> response = inventory.client().post().uri("/reservations")
                .body(Map.of("orderId", UUID.randomUUID(), "lines", List.of(
                        Map.of("skuCode", a, "quantity", 2), Map.of("skuCode", b, "quantity", 3))))
                .retrieve().body(Map.class);
        String id = (String) response.get("reservationId");
        assertThat(inventory.transition(id, "confirm")).isEqualTo(204);
        String function = "reject_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ begin "
                + "if NEW.sku_code = '" + b + "' and NEW.type = 'SHIPMENT' then "
                + "raise exception 'Injected shipment failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger " + function + " before insert on stock_movement for each row execute function "
                + function + "()");
        try {
            assertThat(inventory.transition(id, "consume")).isBetween(400, 599);
            assertThat(inventory.reservationStatus(id)).isEqualTo("CONFIRMED");
            assertThat(inventory.stock(a).onHand()).isEqualTo(10);
            assertThat(inventory.stock(b).onHand()).isEqualTo(10);
            assertThat(inventory.stock(a).committed()).isEqualTo(2);
            assertThat(inventory.stock(b).committed()).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from stock_movement where reservation_id = ?",
                    Long.class, UUID.fromString(id))).isZero();
            inventory.assertMovementBalance(a);
            inventory.assertMovementBalance(b);
        } finally {
            jdbc.execute("drop trigger " + function + " on stock_movement");
            jdbc.execute("drop function " + function + "()");
        }
        assertThat(inventory.transition(id, "consume")).isEqualTo(204);
        inventory.assertMovementBalance(a);
        inventory.assertMovementBalance(b);
    }}
