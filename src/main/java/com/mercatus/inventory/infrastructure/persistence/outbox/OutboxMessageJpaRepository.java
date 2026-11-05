package com.mercatus.inventory.infrastructure.persistence.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessageEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO outbox_message (id, topic, aggregate_id, type, payload, created_at)
            VALUES (:id, :topic, :aggregateId, :type, CAST(:payload AS jsonb), :createdAt)
            """, nativeQuery = true)
    int insert(
            @Param("id") UUID id,
            @Param("topic") String topic,
            @Param("aggregateId") String aggregateId,
            @Param("type") String type,
            @Param("payload") String payload,
            @Param("createdAt") Instant createdAt);

    @Query(value = """
            SELECT * FROM outbox_message
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessageEntity> lockPending(@Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE outbox_message SET published_at = :publishedAt WHERE id = :id", nativeQuery = true)
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    @Query(value = "SELECT count(*) FROM outbox_message WHERE published_at IS NULL", nativeQuery = true)
    long countPending();
}
