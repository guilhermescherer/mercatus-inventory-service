package com.mercatus.inventory.integration.expiration;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase.Outcome;
import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase.ReserveForOrderCommand;
import com.mercatus.inventory.infrastructure.messaging.contract.ContractReader;
import com.mercatus.inventory.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.inventory.support.InventoryFixture;
import java.util.List;
import java.util.UUID;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inventory.reservation.expiry-interval-ms=3600000")
class ExpirationEventIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContractReader reader;

    @Autowired
    ReserveForOrderUseCase reserveForOrder;

    @Autowired
    ExpireReservationUseCase expireReservation;

    private InventoryFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(port, jdbc);
    }

    @Test
    void expiringASagaReservationRecordsReservationExpiredWithTheOrderCorrelation() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = sagaReservation(orderId, sku, "corr-" + orderId);
        fixture.makeDue(reservationId);

        assertThat(expireReservation.expire(UUID.fromString(reservationId))).isTrue();

        MessageEnvelope event = onlyExpiredEvent(orderId);
        assertThat(event.type()).isEqualTo("inventory.ReservationExpired");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(orderId.toString());
        assertThat(event.correlationId()).isEqualTo("corr-" + orderId);
        assertThat(event.causationId()).isNull();
        assertThat(event.payload().get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(event.payload().get("reservationId").asString()).isEqualTo(reservationId);
        assertThat(event.payload().get("expiresAt").asString()).isNotBlank();
        assertThat(fixture.stock(sku).reserved()).isZero();
    }

    @Test
    void reservationWithoutCorrelationUsesTheOrderIdAsCorrelation() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        fixture.makeDue(reservationId);

        expireReservation.expire(UUID.fromString(reservationId));

        assertThat(onlyExpiredEvent(orderId).correlationId()).isEqualTo(orderId.toString());
    }

    @Test
    void expiringAgainDoesNotRecordASecondEvent() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        fixture.makeDue(reservationId);

        assertThat(expireReservation.expire(UUID.fromString(reservationId))).isTrue();
        assertThat(expireReservation.expire(UUID.fromString(reservationId))).isFalse();

        assertThat(expiredEvents(orderId)).hasSize(1);
    }

    @Test
    void failingToRecordTheEventRollsBackTheExpiration() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        fixture.makeDue(reservationId);

        jdbc.execute("ALTER TABLE outbox_message RENAME TO outbox_message_offline");
        try {
            assertThatThrownBy(() -> expireReservation.expire(UUID.fromString(reservationId)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE outbox_message_offline RENAME TO outbox_message");
        }

        assertThat(fixture.reservationStatus(reservationId)).isEqualTo("RESERVED");
        assertThat(fixture.stock(sku).reserved()).isEqualTo(2);
        assertThat(expiredEvents(orderId)).isEmpty();

        assertThat(expireReservation.expire(UUID.fromString(reservationId))).isTrue();
        assertThat(expiredEvents(orderId)).hasSize(1);
        assertThat(fixture.stock(sku).reserved()).isZero();
    }

    private String sagaReservation(UUID orderId, String sku, String correlationId) {
        var outcome = reserveForOrder.reserve(new ReserveForOrderCommand(orderId,
                List.of(new ReserveForOrderCommand.Line(sku, 2)), correlationId));
        assertThat(outcome).isInstanceOf(Outcome.Reserved.class);
        return ((Outcome.Reserved) outcome).reservationId().toString();
    }

    private MessageEnvelope onlyExpiredEvent(UUID orderId) {
        List<String> events = expiredEvents(orderId);
        assertThat(events).hasSize(1);
        return reader.readEnvelope(events.getFirst());
    }

    private List<String> expiredEvents(UUID orderId) {
        return jdbc.queryForList("""
                SELECT payload::text FROM outbox_message
                WHERE aggregate_id = ? AND type = 'inventory.ReservationExpired'
                """, String.class, orderId.toString());
    }
}
