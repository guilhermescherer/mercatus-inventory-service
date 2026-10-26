package com.mercatus.inventory.application.reservation.port.out;

import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationDeadline;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

    Reservation save(Reservation reservation);

    Optional<Reservation> findByOrderId(UUID orderId);

    Optional<Reservation> findByIdForUpdate(UUID id);

    boolean tryCreate(Reservation reservation);

    List<ReservationDeadline> findDueForExpiration(Instant now, ReservationDeadline after, int limit);
}
