package com.mercatus.inventory.infrastructure.messaging.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxStore {

    private final OutboxMessageJpaRepository repository;

    OutboxStore(OutboxMessageJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxMessage message) {
        repository.insert(message.id(), message.topic(), message.aggregateId(), message.type(), message.payload(),
                message.createdAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutboxMessage> lockPending(int limit) {
        return repository.lockPending(limit).stream()
                .map(OutboxMessageEntity::toMessage)
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markPublished(UUID id, Instant publishedAt) {
        repository.markPublished(id, publishedAt);
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return repository.countPending();
    }
}
