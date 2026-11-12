package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.reservation.port.in.ReleaseForOrderUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.ReservationReleaser;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReleaseForOrderService implements ReleaseForOrderUseCase {

    private final ReservationRepository reservationRepository;
    private final ReservationReleaser releaser;

    public ReleaseForOrderService(ReservationRepository reservationRepository, ReservationReleaser releaser) {
        this.reservationRepository = reservationRepository;
        this.releaser = releaser;
    }

    @Override
    @Transactional
    public Outcome release(UUID orderId, UUID reservationId) {
        var found = reservationRepository.findByIdForUpdate(reservationId);
        if (found.isEmpty()) {
            return new Outcome.Rejected("reservation not found");
        }

        var reservation = found.get();
        if (!reservation.orderId().equals(orderId)) {
            return new Outcome.Rejected("reservation belongs to another order");
        }
        if (reservation.status() == ReservationStatus.EXPIRED) {
            return new Outcome.AlreadyExpired(reservation.id(), reservation.expiresAt());
        }
        if (!reservation.canRelease()) {
            return new Outcome.Rejected("reservation is " + reservation.status());
        }

        releaser.release(reservation);
        return new Outcome.Released(reservation.id());
    }
}
