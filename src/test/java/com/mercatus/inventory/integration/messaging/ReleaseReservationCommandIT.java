package com.mercatus.inventory.integration.messaging;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationUseCase;
import com.mercatus.inventory.infrastructure.messaging.contract.ContractReader;
import com.mercatus.inventory.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.inventory.support.InventoryFixture;
import com.mercatus.inventory.support.KafkaProbe;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "inventory.messaging.outbox.poll-interval-ms=200",
                "inventory.reservation.expiry-interval-ms=3600000"
        })
@DirtiesContext
class ReleaseReservationCommandIT {

    private static final String COMMANDS = "inventory.commands.v1";
    private static final String EVENTS = "inventory.events.v1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContractReader reader;

    @Autowired
    ExpireReservationUseCase expireReservation;

    private InventoryFixture fixture;
    private KafkaProbe events;

    @BeforeAll
    static void createTopics() {
        KafkaProbe.createTopics(kafka.getBootstrapServers(), COMMANDS, EVENTS);
    }

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(port, jdbc);
        events = new KafkaProbe(kafka.getBootstrapServers(), EVENTS);
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    void releaseReturnsReservedStockAndRepliesReservationReleased() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        UUID commandId = UUID.randomUUID();

        send(orderId, command(commandId, orderId, reservationId));

        MessageEnvelope reply = awaitReply(commandId);
        assertThat(reply.type()).isEqualTo("inventory.ReservationReleased");
        assertThat(reply.aggregateId()).isEqualTo(orderId.toString());
        assertThat(reply.correlationId()).isEqualTo("corr-" + orderId);
        assertThat(reply.payload().get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(reply.payload().get("reservationId").asString()).isEqualTo(reservationId);
        assertThat(fixture.reservationStatus(reservationId)).isEqualTo("RELEASED");
        assertThat(reserved(sku)).isZero();
        assertThat(fixture.stock(sku).available()).isEqualTo(5);
    }

    @Test
    void repeatedMessageIdHasASingleEffectAndASingleReply() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        UUID commandId = UUID.randomUUID();
        String command = command(commandId, orderId, reservationId);

        send(orderId, command);
        send(orderId, command);

        awaitReply(commandId);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> replies(commandId).size() == 1);
        assertThat(reserved(sku)).isZero();
        assertThat(fixture.stock(sku).available()).isEqualTo(5);
    }

    @Test
    void secondReleaseCommandRepliesAgainWithoutReturningStockTwice() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        send(orderId, command(first, orderId, reservationId));
        send(orderId, command(second, orderId, reservationId));

        assertThat(awaitReply(first).type()).isEqualTo("inventory.ReservationReleased");
        assertThat(awaitReply(second).type()).isEqualTo("inventory.ReservationReleased");
        assertThat(reserved(sku)).isZero();
        assertThat(fixture.stock(sku).available()).isEqualTo(5);
    }

    @Test
    void releaseOfExpiredReservationRepliesReservationExpiredWithoutTouchingStock() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        fixture.makeDue(reservationId);
        assertThat(expireReservation.expire(UUID.fromString(reservationId))).isTrue();
        UUID commandId = UUID.randomUUID();

        send(orderId, command(commandId, orderId, reservationId));

        MessageEnvelope reply = awaitReply(commandId);
        assertThat(reply.type()).isEqualTo("inventory.ReservationExpired");
        assertThat(reply.payload().get("reservationId").asString()).isEqualTo(reservationId);
        assertThat(reply.payload().get("expiresAt").asString()).isNotBlank();
        assertThat(fixture.reservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(reserved(sku)).isZero();
        assertThat(fixture.stock(sku).available()).isEqualTo(5);
    }

    @Test
    void rejectedReleasesChangeNothingAndDoNotBlockTheNextCommand() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        String reservationId = fixture.reserve(orderId, sku, 2);
        UUID unknown = UUID.randomUUID();
        UUID otherOrder = UUID.randomUUID();
        UUID valid = UUID.randomUUID();

        send(orderId, command(unknown, orderId, UUID.randomUUID().toString()));
        send(otherOrder, command(otherOrder, otherOrder, reservationId));
        send(orderId, command(valid, orderId, reservationId));

        assertThat(awaitReply(valid).type()).isEqualTo("inventory.ReservationReleased");
        assertThat(replies(unknown)).isEmpty();
        assertThat(replies(otherOrder)).isEmpty();
        assertThat(inboxed(unknown)).isTrue();
        assertThat(inboxed(otherOrder)).isTrue();
    }

    private void send(UUID orderId, String command) {
        KafkaProbe.send(kafka.getBootstrapServers(), COMMANDS, orderId.toString(), command);
    }

    private String command(UUID messageId, UUID orderId, String reservationId) {
        return """
                {
                  "messageId": "%s",
                  "type": "inventory.ReleaseReservation",
                  "schemaVersion": 1,
                  "occurredAt": "2026-11-06T20:00:00Z",
                  "aggregateId": "%s",
                  "correlationId": "corr-%s",
                  "causationId": null,
                  "payload": { "orderId": "%s", "reservationId": "%s" }
                }
                """.formatted(messageId, orderId, orderId, orderId, reservationId);
    }

    private MessageEnvelope awaitReply(UUID commandId) {
        var found = events.await(record -> repliesTo(record, commandId), 1, Duration.ofSeconds(60));
        assertThat(found).as("reply to command %s", commandId).isNotEmpty();
        return reader.readEnvelope(found.getFirst().value());
    }

    private List<ConsumerRecord<String, String>> replies(UUID commandId) {
        return events.await(record -> repliesTo(record, commandId), Integer.MAX_VALUE, Duration.ofMillis(300));
    }

    private boolean repliesTo(ConsumerRecord<String, String> record, UUID commandId) {
        return commandId.toString().equals(reader.readEnvelope(record.value()).causationId());
    }

    private boolean inboxed(UUID messageId) {
        return jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE message_id = ?", Long.class,
                messageId) == 1;
    }

    private long reserved(String sku) {
        return jdbc.queryForObject("SELECT reserved FROM stock_item WHERE sku_code = ?", Long.class, sku);
    }
}
