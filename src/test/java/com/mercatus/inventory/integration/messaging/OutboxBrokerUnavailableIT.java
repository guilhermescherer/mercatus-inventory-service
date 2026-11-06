package com.mercatus.inventory.integration.messaging;

import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxStore;
import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxWriter;
import com.mercatus.inventory.infrastructure.messaging.outbox.OutboxWriter.OutgoingMessage;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "inventory.messaging.outbox.poll-interval-ms=200",
        "inventory.messaging.outbox.send-timeout=2s",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000"
})
@DirtiesContext
class OutboxBrokerUnavailableIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxStore outbox;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void serviceStartsAndKeepsCommittedMessagesPendingWithoutBroker() {
        UUID orderId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> writer.enqueue("inventory.events.v1",
                new OutgoingMessage("inventory.Example", 1, orderId.toString(), "corr-1", null,
                        Map.of("orderId", orderId.toString()))));

        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6))
                .until(() -> outbox.countPending() == 1);
        assertThat(outbox.countPending()).isEqualTo(1);
    }
}
