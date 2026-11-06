package com.mercatus.inventory.infrastructure.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_message")
class OutboxMessageEntity {

    @Id
    UUID id;

    String topic;

    @Column(name = "aggregate_id")
    String aggregateId;

    String type;

    @JdbcTypeCode(SqlTypes.JSON)
    String payload;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "published_at")
    Instant publishedAt;

    protected OutboxMessageEntity() {
    }

    OutboxMessage toMessage() {
        return new OutboxMessage(id, topic, aggregateId, type, payload, createdAt);
    }
}
