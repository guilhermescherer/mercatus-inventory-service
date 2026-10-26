package com.mercatus.inventory;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationsUseCase;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "inventory.reservation.expiry-interval-ms=3600000",
        "inventory.reservation.expiry-batch-size=1"
})
class ReservationExpiryBatchIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExpireReservationsUseCase expireReservations;

    InventoryFixture inventory;

    @BeforeEach
    void setUp() {
        inventory = new InventoryFixture(port, jdbc);
    }

    @Test
    void failingFirstPageDoesNotStarveLaterReservationsAndIsRetriedAfterWrap() {
        String brokenSku = inventory.uniqueSkuCode();
        String healthySku = inventory.uniqueSkuCode();
        inventory.receive(brokenSku, 5);
        inventory.receive(healthySku, 5);
        String broken = inventory.reserve(UUID.randomUUID(), brokenSku, 2);
        String healthy = inventory.reserve(UUID.randomUUID(), healthySku, 2);
        inventory.makeDue(healthy);
        jdbc.update("update reservation set expires_at = current_timestamp - interval '2 minutes' where id = ?",
                UUID.fromString(broken));
        jdbc.update("update stock_item set reserved = 0 where sku_code = ?", brokenSku);

        assertThat(expireReservations.expireDue()).isZero();
        assertThat(inventory.reservationStatus(broken)).isEqualTo("RESERVED");
        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(inventory.reservationStatus(healthy)).isEqualTo("EXPIRED");
        assertThat(inventory.stock(healthySku).reserved()).isZero();
        assertThat(expireReservations.expireDue()).isZero();

        jdbc.update("update stock_item set reserved = 2 where sku_code = ?", brokenSku);
        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(inventory.reservationStatus(broken)).isEqualTo("EXPIRED");
        assertThat(expireReservations.expireDue()).isZero();
        inventory.assertMovementBalance(brokenSku);
        inventory.assertMovementBalance(healthySku);
    }

    @Test
    void reservationsSharingADeadlineAreAllVisitedAcrossBatches() {
        List<String> ids = new ArrayList<>();
        Instant deadline = Instant.now().minusSeconds(60);
        for (int index = 0; index < 3; index++) {
            String sku = inventory.uniqueSkuCode();
            inventory.receive(sku, 5);
            String id = inventory.reserve(UUID.randomUUID(), sku, 2);
            jdbc.update("update reservation set expires_at = ? where id = ?",
                    Timestamp.from(deadline), UUID.fromString(id));
            ids.add(id);
        }

        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(expireReservations.expireDue()).isEqualTo(1);
        assertThat(expireReservations.expireDue()).isZero();
        assertThat(ids).allSatisfy(id -> assertThat(inventory.reservationStatus(id)).isEqualTo("EXPIRED"));
    }
}
