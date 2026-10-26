package com.mercatus.inventory.infrastructure.persistence.reservation;

import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationDeadline;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
class ReservationRepositoryAdapter implements ReservationRepository {

    private final ReservationJpaRepository jpaRepository;

    ReservationRepositoryAdapter(ReservationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Reservation save(Reservation reservation) {
        ReservationEntity saved = jpaRepository.save(ReservationMapper.toEntity(reservation));
        return ReservationMapper.toDomain(saved);
    }

    @Override
    public Optional<Reservation> findByOrderId(UUID orderId) {
        return jpaRepository.findByOrderId(orderId).map(ReservationMapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<Reservation> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(ReservationMapper::toDomain);
    }

    @Override
    @Transactional
    public boolean tryCreate(Reservation reservation) {
        return jpaRepository.insertIfAbsent(reservation.id(), reservation.orderId(),
                reservation.status().name(), reservation.createdAt(), reservation.expiresAt()) == 1;
    }

    @Override
    public List<ReservationDeadline> findDueForExpiration(Instant now, ReservationDeadline after, int limit) {
        var page = PageRequest.of(0, limit);
        if (after == null) {
            return jpaRepository.findDueForExpiration(ReservationStatus.RESERVED, now, page);
        }
        return jpaRepository.findDueForExpirationAfter(ReservationStatus.RESERVED, now,
                after.expiresAt(), after.reservationId(), page);
    }
}
