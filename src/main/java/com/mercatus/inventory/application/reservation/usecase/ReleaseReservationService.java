package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.reservation.port.in.ReleaseReservationUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.ReservationReleaser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReleaseReservationService implements ReleaseReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final ReservationReleaser releaser;

    public ReleaseReservationService(ReservationRepository reservationRepository, ReservationReleaser releaser) {
        this.reservationRepository = reservationRepository;
        this.releaser = releaser;
    }

    @Override
    @Transactional
    public void release(UUID reservationId) {
        var reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        releaser.release(reservation);
    }
}
