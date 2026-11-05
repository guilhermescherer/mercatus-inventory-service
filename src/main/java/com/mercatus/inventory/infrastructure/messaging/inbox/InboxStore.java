package com.mercatus.inventory.infrastructure.messaging.inbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboxStore {

    private final InboxMessageJpaRepository repository;

    InboxStore(InboxMessageJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean register(UUID messageId, String type, Instant consumedAt) {
        return repository.insertIfAbsent(messageId, type, consumedAt) == 1;
    }

    @Transactional(readOnly = true)
    public boolean contains(UUID messageId) {
        return repository.existsById(messageId);
    }
}
