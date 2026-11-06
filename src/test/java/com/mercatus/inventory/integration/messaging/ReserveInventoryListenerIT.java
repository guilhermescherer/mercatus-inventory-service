package com.mercatus.inventory.integration.messaging;

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
        properties = "inventory.messaging.outbox.poll-interval-ms=200")
@DirtiesContext
class ReserveInventoryListenerIT {

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
    void commandWithStockReservesAndRepliesInventoryReserved() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        send(command(commandId, orderId, sku, 2));

        MessageEnvelope reply = awaitReply(commandId);
        assertThat(reply.type()).isEqualTo("inventory.InventoryReserved");
        assertThat(reply.aggregateId()).isEqualTo(orderId.toString());
        assertThat(reply.correlationId()).isEqualTo("corr-" + orderId);
        assertThat(reply.payload().get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(reply.payload().get("reservationId").asString()).isNotBlank();
        assertThat(reply.payload().get("expiresAt").asString()).isNotBlank();
        assertThat(reserved(sku)).isEqualTo(2);
    }

    @Test
    void commandWithoutStockRepliesOutOfStockAndReservesNothing() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 1);
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        send(command(commandId, orderId, sku, 3));

        MessageEnvelope reply = awaitReply(commandId);
        assertThat(reply.type()).isEqualTo("inventory.InventoryReservationFailed");
        assertThat(reply.payload().get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(reserved(sku)).isZero();
        assertThat(reservations(orderId)).isZero();
    }

    @Test
    void unknownSkuIsTreatedAsOutOfStock() {
        UUID commandId = UUID.randomUUID();

        send(command(commandId, UUID.randomUUID(), fixture.uniqueSkuCode(), 1));

        assertThat(awaitReply(commandId).type()).isEqualTo("inventory.InventoryReservationFailed");
    }

    @Test
    void repeatedMessageIdHasASingleEffectAndASingleReply() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String command = command(commandId, orderId, sku, 2);

        send(command);
        send(command);

        awaitReply(commandId);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> replies(commandId).size() == 1);
        assertThat(reserved(sku)).isEqualTo(2);
        assertThat(reservations(orderId)).isEqualTo(1);
    }

    @Test
    void differentCommandsForTheSameOrderShareOneReservation() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        send(command(first, orderId, sku, 2));
        send(command(second, orderId, sku, 2));

        var firstReply = awaitReply(first);
        var secondReply = awaitReply(second);
        assertThat(secondReply.payload().get("reservationId").asString())
                .isEqualTo(firstReply.payload().get("reservationId").asString());
        assertThat(reserved(sku)).isEqualTo(2);
    }

    @Test
    void brokenMessageIsSkippedWithoutBlockingTheNextOne() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID commandId = UUID.randomUUID();

        KafkaProbe.send(kafka.getBootstrapServers(), COMMANDS, "broken", "{ not json");
        send(command(commandId, UUID.randomUUID(), sku, 1));

        assertThat(awaitReply(commandId).type()).isEqualTo("inventory.InventoryReserved");
    }

    @Test
    void technicalFailureIsRetriedUntilTheDatabaseRecovers() {
        String sku = fixture.uniqueSkuCode();
        fixture.receive(sku, 5);
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        jdbc.execute("ALTER TABLE inbox_message RENAME TO inbox_message_offline");
        try {
            send(command(commandId, orderId, sku, 2));

            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> replies(commandId).isEmpty());
            assertThat(reservations(orderId)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE inbox_message_offline RENAME TO inbox_message");
        }

        assertThat(awaitReply(commandId).type()).isEqualTo("inventory.InventoryReserved");
        assertThat(reservations(orderId)).isEqualTo(1);
        assertThat(reserved(sku)).isEqualTo(2);
    }

    private void send(String command) {
        String key = command.substring(command.indexOf("\"aggregateId\": \"") + 16).split("\"")[0];
        KafkaProbe.send(kafka.getBootstrapServers(), COMMANDS, key, command);
    }

    private String command(UUID messageId, UUID orderId, String sku, long quantity) {
        return """
                {
                  "messageId": "%s",
                  "type": "inventory.ReserveInventory",
                  "schemaVersion": 1,
                  "occurredAt": "2026-11-04T20:00:00Z",
                  "aggregateId": "%s",
                  "correlationId": "corr-%s",
                  "causationId": null,
                  "payload": { "orderId": "%s", "lines": [ { "skuCode": "%s", "quantity": %d } ] }
                }
                """.formatted(messageId, orderId, orderId, orderId, sku, quantity);
    }

    private MessageEnvelope awaitReply(UUID commandId) {
        var found = events.await(record -> repliesTo(record, commandId), 1,
                Duration.ofSeconds(60));
        assertThat(found).as("reply to command %s", commandId).isNotEmpty();
        return reader.readEnvelope(found.getFirst().value());
    }

    private List<ConsumerRecord<String, String>> replies(UUID commandId) {
        return events.await(record -> repliesTo(record, commandId),
                Integer.MAX_VALUE, Duration.ofMillis(300));
    }

    private boolean repliesTo(ConsumerRecord<String, String> record, UUID commandId) {
        return commandId.toString().equals(reader.readEnvelope(record.value()).causationId());
    }

    private long reserved(String sku) {
        return jdbc.queryForObject("SELECT reserved FROM stock_item WHERE sku_code = ?", Long.class, sku);
    }

    private long reservations(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE order_id = ?", Long.class, orderId);
    }
}
