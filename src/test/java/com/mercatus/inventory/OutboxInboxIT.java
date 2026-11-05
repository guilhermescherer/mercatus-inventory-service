package com.mercatus.inventory;

import com.mercatus.inventory.infrastructure.messaging.ContractReader;
import com.mercatus.inventory.infrastructure.messaging.MessageEnvelope;
import com.mercatus.inventory.infrastructure.messaging.OutboxWriter;
import com.mercatus.inventory.infrastructure.messaging.OutboxWriter.OutgoingMessage;
import com.mercatus.inventory.infrastructure.persistence.inbox.InboxStore;
import com.mercatus.inventory.support.KafkaProbe;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {
        "inventory.messaging.outbox.poll-interval-ms=200",
        "inventory.messaging.outbox.send-timeout=3s",
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000"
})
@DirtiesContext
class OutboxInboxIT {

    private static final String EVENTS = "inventory.events.v1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Autowired
    OutboxWriter writer;

    @Autowired
    InboxStore inbox;

    @Autowired
    ContractReader reader;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void createTopics() {
        KafkaProbe.createTopics(kafka.getBootstrapServers(), "inventory.commands.v1", EVENTS);
    }

    @Test
    void committedMessageIsPublishedOnceWithEnvelopeAndOrderKey() {
        UUID orderId = UUID.randomUUID();
        MessageEnvelope envelope = transactions.execute(status -> writer.enqueue(EVENTS, message(orderId)));

        try (var probe = new KafkaProbe(kafka.getBootstrapServers(), EVENTS)) {
            var records = probe.await(record -> record.value().contains(envelope.messageId().toString()), 1,
                    Duration.ofSeconds(30));

            assertThat(records).hasSize(1);
            var record = records.getFirst();
            assertThat(record.key()).isEqualTo(orderId.toString());

            var read = reader.readEnvelope(record.value());
            assertThat(read.messageId()).isEqualTo(envelope.messageId());
            assertThat(read.type()).isEqualTo("inventory.Example");
            assertThat(read.schemaVersion()).isEqualTo(1);
            assertThat(read.aggregateId()).isEqualTo(orderId.toString());
            assertThat(read.correlationId()).isEqualTo("corr-" + orderId);
            assertThat(read.causationId()).isEqualTo("cause-1");
            assertThat(read.payload().get("orderId").asString()).isEqualTo(orderId.toString());
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> publishedAt(envelope.messageId()) != null);
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxMessage() {
        var id = new AtomicReference<UUID>();

        transactions.executeWithoutResult(status -> {
            id.set(writer.enqueue(EVENTS, message(UUID.randomUUID())).messageId());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE id = ?", Long.class, id.get()))
                .isZero();
    }

    @Test
    void enqueueRequiresTheBusinessTransaction() {
        assertThatThrownBy(() -> writer.enqueue(EVENTS, message(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    // Cenário principal da outbox: o Kafka "some" (pausamos o container), o negócio
    // continua gravando, e quando o Kafka volta a mensagem sai sozinha.
    void messageCommittedWhileBrokerIsUnreachableIsPublishedWhenItReturns() {
        var docker = kafka.getDockerClient();
        UUID orderId = UUID.randomUUID();
        UUID messageId;

        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            messageId = transactions.execute(status -> writer.enqueue(EVENTS, message(orderId))).messageId();

            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8))
                    .until(() -> publishedAt(messageId) == null);
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        try (var probe = new KafkaProbe(kafka.getBootstrapServers(), EVENTS)) {
            var records = probe.await(record -> record.value().contains(messageId.toString()), 1,
                    Duration.ofSeconds(60));

            assertThat(records).isNotEmpty();
            assertThat(records.getFirst().key()).isEqualTo(orderId.toString());
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> publishedAt(messageId) != null);
    }

    @Test
    // Mesmo messageId duas vezes: só a primeira é aceita.
    void inboxIgnoresRepeatedMessageId() {
        UUID messageId = UUID.randomUUID();

        assertThat(register(messageId))
                .isTrue();
        assertThat(register(messageId))
                .isFalse();
    }

    @Test
    void inboxRegistrationIsUndoneWhenTheTransactionRollsBack() {
        UUID messageId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            assertThat(inbox.register(messageId, "inventory.Example", Instant.now())).isTrue();
            status.setRollbackOnly();
        });

        assertThat(inbox.contains(messageId)).isFalse();
        assertThat(register(messageId))
                .isTrue();
    }

    private boolean register(UUID messageId) {
        Boolean registered = transactions.execute(
                status -> inbox.register(messageId, "inventory.Example", Instant.now()));
        return Boolean.TRUE.equals(registered);
    }

    private OutgoingMessage message(UUID orderId) {
        return new OutgoingMessage("inventory.Example", 1, orderId.toString(), "corr-" + orderId, "cause-1",
                Map.of("orderId", orderId.toString()));
    }

    private Timestamp publishedAt(UUID id) {
        return jdbc.queryForObject("SELECT published_at FROM outbox_message WHERE id = ?", Timestamp.class, id);
    }
}
