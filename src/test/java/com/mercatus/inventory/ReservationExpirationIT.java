package com.mercatus.inventory;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationsUseCase;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.reservation.expiry-interval-ms=3600000")
class ReservationExpirationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExpireReservationUseCase expireReservation;

    @Autowired
    ExpireReservationsUseCase expireReservations;

    InventoryFixture inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryFixture(port, jdbc);
    }

    @Test
    void concurrentExpirersDoNotReleaseOtherReservations() throws Exception {
        String sku = inventory.uniqueSkuCode();
        inventory.receive(sku, 20);
        String id = inventory.reserve(UUID.randomUUID(), sku, 2);
        inventory.reserve(UUID.randomUUID(), sku, 5);
        inventory.makeDue(id);
        List<Boolean> expired = ConcurrentRequests.run(List.of(
                () -> expireReservation.expire(UUID.fromString(id)),
                () -> expireReservation.expire(UUID.fromString(id))));
        assertThat(expired).containsExactlyInAnyOrder(true, false);
        assertThat(inventory.reservationStatus(id)).isEqualTo("EXPIRED");
        assertThat(inventory.stock(sku).reserved()).isEqualTo(5);
        inventory.assertMovementBalance(sku);
    }

    @Test
    void expirationFailureDoesNotRollbackOtherCandidatesAndCanBeRetried() {
        String broken = inventory.uniqueSkuCode();
        String healthy = inventory.uniqueSkuCode();
        inventory.receive(broken, 5);
        inventory.receive(healthy, 5);
        String first = inventory.reserve(UUID.randomUUID(), broken, 2);
        String second = inventory.reserve(UUID.randomUUID(), healthy, 2);
        inventory.makeDue(first);
        inventory.makeDue(second);
        jdbc.update("update reservation set expires_at = current_timestamp - interval '2 minutes' where id = ?",
                UUID.fromString(first));
        jdbc.update("update stock_item set reserved = 0 where sku_code = ?", broken);
        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(inventory.reservationStatus(first)).isEqualTo("RESERVED");
        assertThat(inventory.reservationStatus(second)).isEqualTo("EXPIRED");
        assertThat(inventory.stock(healthy).reserved()).isZero();
        jdbc.update("update stock_item set reserved = 2 where sku_code = ?", broken);
        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(expireReservations.expireDue()).isZero();
        inventory.assertMovementBalance(broken);
        inventory.assertMovementBalance(healthy);
    }

}
