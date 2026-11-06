package com.mercatus.inventory.infrastructure.messaging.outbox;

import com.mercatus.inventory.infrastructure.messaging.config.MessagingProperties;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MessagingProperties properties;

    OutboxPublisher(OutboxStore outbox, KafkaTemplate<String, String> kafka, TransactionTemplate transactions,
            Clock clock, MessagingProperties properties) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${inventory.messaging.outbox.poll-interval-ms:500}")
    public void publishPending() {
        int batchSize = properties.outbox().batchSize();
        Batch batch;

        do {
            batch = transactions.execute(status -> publishBatch(batchSize));
        } while (batch != null && batch.complete() && batch.published() == batchSize);
    }

    private Batch publishBatch(int batchSize) {
        int published = 0;

        for (var message : outbox.lockPending(batchSize)) {
            if (!send(message)) {
                return new Batch(published, false);
            }

            outbox.markPublished(message.id(), clock.instant());
            published++;
        }

        return new Batch(published, true);
    }

    private boolean send(OutboxMessage message) {
        long timeout = properties.outbox().sendTimeout().toMillis();

        try {
            kafka.send(message.topic(), message.aggregateId(), message.payload())
                    .get(timeout, TimeUnit.MILLISECONDS);
            log.info("Published outbox message {} of type {} to {}", message.id(), message.type(), message.topic());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("Outbox message {} not published, will retry: {}", message.id(), e.getMessage());
            return false;
        }
    }

    private record Batch(int published, boolean complete) {
    }
}
