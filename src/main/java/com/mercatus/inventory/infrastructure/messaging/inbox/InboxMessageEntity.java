package com.mercatus.inventory.infrastructure.messaging.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_message")
class InboxMessageEntity {

    @Id
    @Column(name = "message_id")
    UUID messageId;

    String type;

    @Column(name = "consumed_at")
    Instant consumedAt;

    protected InboxMessageEntity() {
    }
}
