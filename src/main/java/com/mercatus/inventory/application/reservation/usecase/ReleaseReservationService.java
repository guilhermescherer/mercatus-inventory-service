package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.reservation.port.in.ReleaseReservationUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.StockLineLocker;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.BiConsumer;

@Service
public class ReleaseReservationService implements ReleaseReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final StockLineLocker stockLineLocker;

    public ReleaseReservationService(ReservationRepository reservationRepository, StockLineLocker stockLineLocker) {
        this.reservationRepository = reservationRepository;
        this.stockLineLocker = stockLineLocker;
    }

    @Override
    @Transactional
    public void release(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        ReservationStatus before = reservation.status();
        if (before == ReservationStatus.RELEASED) {
            return;
        }
        reservation.release();
        BiConsumer<StockItem, Long> effect = before == ReservationStatus.CONFIRMED
                ? StockItem::releaseCommitted
                : StockItem::releaseReserved;
        stockLineLocker.lockApplyAndSave(reservation.lines(), effect);
        reservationRepository.save(reservation);
    }
}
