package com.mercatus.inventory.infrastructure.messaging.inbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InboxMessageJpaRepository extends JpaRepository<InboxMessageEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO inbox_message (message_id, type, consumed_at)
            VALUES (:messageId, :type, :consumedAt)
            ON CONFLICT (message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("messageId") UUID messageId,
            @Param("type") String type,
            @Param("consumedAt") Instant consumedAt);
}
