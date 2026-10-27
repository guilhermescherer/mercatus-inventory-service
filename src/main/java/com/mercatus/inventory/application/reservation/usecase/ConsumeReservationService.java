package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.reservation.port.in.ConsumeReservationUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.StockLineLocker;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.application.stock.port.out.StockMovementRepository;
import com.mercatus.inventory.domain.stock.StockMovement;
import com.mercatus.inventory.domain.stock.StockItem;
import com.mercatus.inventory.domain.reservation.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ConsumeReservationService implements ConsumeReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final StockLineLocker stockLineLocker;
    private final StockMovementRepository movementRepository;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ConsumeReservationService(ReservationRepository reservationRepository, StockLineLocker stockLineLocker,
                                     StockMovementRepository movementRepository, Clock clock,
                                     Supplier<UUID> identifiers) {
        this.movementRepository = movementRepository;
        this.clock = clock;
        this.reservationRepository = reservationRepository;
        this.stockLineLocker = stockLineLocker;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional
    public void consume(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        if (reservation.status() == ReservationStatus.CONSUMED) {
            return;
        }
        reservation.consume();
        stockLineLocker.lockApplyAndSave(reservation.lines(), StockItem::consume);
        Instant now = Instant.now(clock);
        reservation.lines().forEach(line -> movementRepository.append(
                StockMovement.shipment(identifiers.get(), line.skuCode(), line.quantity(), reservation.id(), now)));
        reservationRepository.save(reservation);
    }
}
