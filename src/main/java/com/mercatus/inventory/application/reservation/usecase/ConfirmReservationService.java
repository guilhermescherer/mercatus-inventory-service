package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.reservation.port.in.ConfirmReservationUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.StockLineLocker;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ConfirmReservationService implements ConfirmReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final StockLineLocker stockLineLocker;

    public ConfirmReservationService(ReservationRepository reservationRepository, StockLineLocker stockLineLocker) {
        this.reservationRepository = reservationRepository;
        this.stockLineLocker = stockLineLocker;
    }

    @Override
    @Transactional
    public void confirm(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        if (reservation.status() == ReservationStatus.CONFIRMED) {
            return;
        }
        reservation.confirm();
        stockLineLocker.lockApplyAndSave(reservation.lines(), StockItem::confirmReservation);
        reservationRepository.save(reservation);
    }
}
