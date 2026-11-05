package com.mercatus.inventory.infrastructure.persistence.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(UUID id, String topic, String aggregateId, String type, String payload,
        Instant createdAt) {
}
