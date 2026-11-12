package com.mercatus.inventory.application.reservation.usecase.support;

import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Component;

import java.util.function.BiConsumer;

@Component
public class ReservationReleaser {

    private final ReservationRepository reservationRepository;
    private final StockLineLocker stockLineLocker;

    public ReservationReleaser(ReservationRepository reservationRepository, StockLineLocker stockLineLocker) {
        this.reservationRepository = reservationRepository;
        this.stockLineLocker = stockLineLocker;
    }

    public void release(Reservation reservation) {
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
