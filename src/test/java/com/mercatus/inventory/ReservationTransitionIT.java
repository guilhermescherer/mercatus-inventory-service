package com.mercatus.inventory;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationUseCase;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.reservation.expiry-interval-ms=3600000")
class ReservationTransitionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExpireReservationUseCase expireReservation;

    InventoryFixture inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryFixture(port, jdbc);
    }

    @Test
    void repeatedConcurrentTransitionsApplyOneEffectEvenWithOtherReservationsOnSameSku() throws Exception {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 20);
        String id = inventory.reserve(UUID.randomUUID(), sku, 2);
        String other = inventory.reserve(UUID.randomUUID(), sku, 5);
        List<Callable<Integer>> confirms = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<Integer>) () -> inventory.transition(id, "confirm")).toList();
        assertThat(ConcurrentRequests.run(confirms)).containsOnly(204);
        assertThat(inventory.stock(sku).reserved()).isEqualTo(5);
        assertThat(inventory.stock(sku).committed()).isEqualTo(2);
        List<Callable<Integer>> consumes = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<Integer>) () -> inventory.transition(id, "consume")).toList();
        assertThat(ConcurrentRequests.run(consumes)).containsOnly(204);
        assertThat(inventory.reservationStatus(id)).isEqualTo("CONSUMED");
        assertThat(inventory.stock(sku).onHand()).isEqualTo(18);
        assertThat(inventory.stock(sku).committed()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stock_movement where reservation_id = ?",
                Long.class, UUID.fromString(id))).isEqualTo(1);
        List<Callable<Integer>> releases = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<Integer>) () -> inventory.transition(other, "release")).toList();
        assertThat(ConcurrentRequests.run(releases)).containsOnly(204);
        assertThat(inventory.stock(sku).reserved()).isZero();
        assertThat(inventory.transition(id, "release")).isEqualTo(422);
        assertThat(inventory.transition(other, "confirm")).isEqualTo(422);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void confirmRacingExpiryHasOneLegalOutcome() throws Exception {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 20);
        String id = inventory.reserve(UUID.randomUUID(), sku, 2);
        inventory.reserve(UUID.randomUUID(), sku, 5);
        inventory.makeDue(id);
        List<Integer> results = ConcurrentRequests.run(List.of(
                () -> inventory.transition(id, "confirm"),
                () -> expireReservation.expire(UUID.fromString(id)) ? 1 : 0));
        if (inventory.reservationStatus(id).equals("CONFIRMED")) {
            assertThat(results).containsExactly(204, 0);
            assertThat(inventory.stock(sku).committed()).isEqualTo(2);
        } else {
            assertThat(inventory.reservationStatus(id)).isEqualTo("EXPIRED");
            assertThat(results).containsExactly(422, 1);
            assertThat(inventory.stock(sku).committed()).isZero();
        }
        assertThat(inventory.stock(sku).reserved()).isEqualTo(5);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void releaseRacingConsumeHasOneLegalOutcome() throws Exception {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 20);
        String id = inventory.reserve(UUID.randomUUID(), sku, 2);
        assertThat(inventory.transition(id, "confirm")).isEqualTo(204);
        List<Integer> results = ConcurrentRequests.run(List.of(
                () -> inventory.transition(id, "release"), () -> inventory.transition(id, "consume")));
        if (inventory.reservationStatus(id).equals("RELEASED")) {
            assertThat(results).containsExactly(204, 422);
            assertThat(inventory.stock(sku).onHand()).isEqualTo(20);
        } else {
            assertThat(inventory.reservationStatus(id)).isEqualTo("CONSUMED");
            assertThat(results).containsExactly(422, 204);
            assertThat(inventory.stock(sku).onHand()).isEqualTo(18);
        }
        assertThat(inventory.stock(sku).committed()).isZero();
        assertThat(inventory.stock(sku).reserved()).isZero();
        inventory.assertMovementBalance(sku);
    }

}
