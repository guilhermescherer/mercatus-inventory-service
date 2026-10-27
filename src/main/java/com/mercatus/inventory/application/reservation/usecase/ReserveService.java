package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.reservation.port.in.ReserveUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.reservation.usecase.support.StockLineLocker;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationLine;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ReserveService implements ReserveUseCase {

    private final ReservationRepository reservationRepository;
    private final StockLineLocker stockLineLocker;
    private final Clock clock;
    private final Duration ttl;
    private final Supplier<UUID> identifiers;

    public ReserveService(ReservationRepository reservationRepository,
                          StockLineLocker stockLineLocker,
                          Clock clock,
                          @Value("${inventory.reservation.ttl:PT30M}") Duration ttl,
                          Supplier<UUID> identifiers) {
        this.reservationRepository = reservationRepository;
        this.stockLineLocker = stockLineLocker;
        this.clock = clock;
        this.ttl = ttl;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID reserve(ReserveCommand command) {
        UUID orderId = command.orderId();

        var existing = reservationRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return existing.get().id();
        }

        List<ReservationLine> lines = command.lines().stream()
                .map(l -> new ReservationLine(l.skuCode(), l.quantity()))
                .toList();

        Reservation reservation = Reservation.create(identifiers.get(), orderId, lines, ttl, Instant.now(clock));
        if (!reservationRepository.tryCreate(reservation)) {
            return reservationRepository.findByOrderId(orderId).orElseThrow().id();
        }

        stockLineLocker.lockApplyAndSave(lines, StockItem::reserve);

        return reservationRepository.save(reservation).id();
    }
}
