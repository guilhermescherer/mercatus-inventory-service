package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase;
import com.mercatus.inventory.application.reservation.port.out.ReservationRepository;
import com.mercatus.inventory.application.stock.port.out.StockRepository;
import com.mercatus.inventory.domain.reservation.Reservation;
import com.mercatus.inventory.domain.reservation.ReservationLine;
import com.mercatus.inventory.domain.stock.StockItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReserveForOrderService implements ReserveForOrderUseCase {

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final Clock clock;
    private final Duration ttl;
    private final Supplier<UUID> identifiers;

    public ReserveForOrderService(ReservationRepository reservations, StockRepository stock, Clock clock,
            @Value("${inventory.reservation.ttl:PT30M}") Duration ttl, Supplier<UUID> identifiers) {
        this.reservations = reservations;
        this.stock = stock;
        this.clock = clock;
        this.ttl = ttl;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome reserve(ReserveForOrderCommand command) {
        var existing = reservations.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            return reserved(existing.get());
        }

        Map<String, Long> requested = command.lines().stream()
                .collect(Collectors.toMap(ReserveForOrderCommand.Line::skuCode,
                        ReserveForOrderCommand.Line::quantity, Long::sum, TreeMap::new));

        Map<String, StockItem> locked = stock.findAllForUpdateOrdered(requested.keySet()).stream()
                .collect(Collectors.toMap(StockItem::skuCode, Function.identity()));

        boolean available = requested.entrySet().stream()
                .allMatch(line -> locked.containsKey(line.getKey())
                        && locked.get(line.getKey()).available() >= line.getValue());

        if (!available) {
            return new Outcome.OutOfStock();
        }

        List<ReservationLine> lines = requested.entrySet().stream()
                .map(line -> new ReservationLine(line.getKey(), line.getValue()))
                .toList();

        Reservation reservation = Reservation.create(identifiers.get(), command.orderId(), lines, ttl,
                Instant.now(clock));

        if (!reservations.tryCreate(reservation)) {
            return reserved(reservations.findByOrderId(command.orderId()).orElseThrow());
        }

        lines.forEach(line -> locked.get(line.skuCode()).reserve(line.quantity()));
        stock.saveAll(List.copyOf(locked.values()));

        return reserved(reservations.save(reservation));
    }

    private Outcome reserved(Reservation reservation) {
        return new Outcome.Reserved(reservation.id(), reservation.expiresAt());
    }
}
