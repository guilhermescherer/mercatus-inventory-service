package com.mercatus.inventory.infrastructure.messaging.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inventory.messaging")
public record MessagingProperties(Topics topics, Outbox outbox) {

    public MessagingProperties {
        if (topics == null || isBlank(topics.commands()) || isBlank(topics.events())) {
            throw new IllegalArgumentException("Messaging topics are required");
        }
        if (outbox == null || outbox.batchSize() <= 0 || outbox.sendTimeout() == null
                || outbox.sendTimeout().isNegative() || outbox.sendTimeout().isZero()) {
            throw new IllegalArgumentException("Outbox batch size and send timeout must be positive");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Topics(String commands, String events) {
    }

    public record Outbox(int batchSize, Duration sendTimeout) {
    }
}
