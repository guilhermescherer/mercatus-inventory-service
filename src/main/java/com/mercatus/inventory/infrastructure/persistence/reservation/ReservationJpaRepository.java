package com.mercatus.inventory.infrastructure.persistence.reservation;

import com.mercatus.inventory.domain.reservation.ReservationDeadline;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {

    Optional<ReservationEntity> findByOrderId(UUID orderId);

    @Modifying
    @Query(value = """
            insert into reservation (id, order_id, status, created_at, expires_at)
            values (:id, :orderId, :status, :createdAt, :expiresAt)
            on conflict (order_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("orderId") UUID orderId,
                       @Param("status") String status, @Param("createdAt") Instant createdAt,
                       @Param("expiresAt") Instant expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservationEntity r where r.id = :id")
    Optional<ReservationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select new com.mercatus.inventory.domain.reservation.ReservationDeadline(r.id, r.expiresAt)
              from ReservationEntity r
             where r.status = :status and r.expiresAt <= :now
             order by r.expiresAt, r.id
            """)
    List<ReservationDeadline> findDueForExpiration(@Param("status") ReservationStatus status,
                                                 @Param("now") Instant now, Pageable pageable);

    @Query("""
            select new com.mercatus.inventory.domain.reservation.ReservationDeadline(r.id, r.expiresAt)
              from ReservationEntity r
             where r.status = :status and r.expiresAt <= :now
               and (r.expiresAt > :afterExpiresAt
                    or (r.expiresAt = :afterExpiresAt and r.id > :afterId))
             order by r.expiresAt, r.id
            """)
    List<ReservationDeadline> findDueForExpirationAfter(@Param("status") ReservationStatus status,
                                                      @Param("now") Instant now,
                                                      @Param("afterExpiresAt") Instant afterExpiresAt,
                                                      @Param("afterId") UUID afterId, Pageable pageable);
}
