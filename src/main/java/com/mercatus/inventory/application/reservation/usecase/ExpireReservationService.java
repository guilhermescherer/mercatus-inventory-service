package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.reservation.port.in.ExpireReservationUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.StockLineLocker;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ExpireReservationService implements ExpireReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final StockLineLocker stockLineLocker;
    private final Clock clock;

    public ExpireReservationService(ReservationRepository reservationRepository,
                                    StockLineLocker stockLineLocker, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.stockLineLocker = stockLineLocker;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(UUID reservationId) {
        var reservation = reservationRepository.findByIdForUpdate(reservationId);
        if (reservation.isEmpty() || !reservation.get().isDueForExpiration(Instant.now(clock))) {
            return false;
        }
        var due = reservation.get();
        due.expire();
        stockLineLocker.lockApplyAndSave(due.lines(), StockItem::releaseReserved);
        reservationRepository.save(due);
        return true;
    }
}
