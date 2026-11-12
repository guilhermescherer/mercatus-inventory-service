package com.mercatus.inventory.application.reservation.usecase.support;

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
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationCreator {

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final Clock clock;
    private final Duration ttl;
    private final Supplier<UUID> identifiers;

    public ReservationCreator(ReservationRepository reservations, StockRepository stock, Clock clock,
            @Value("${inventory.reservation.ttl:PT30M}") Duration ttl, Supplier<UUID> identifiers) {
        this.reservations = reservations;
        this.stock = stock;
        this.clock = clock;
        this.ttl = ttl;
        this.identifiers = identifiers;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result create(UUID orderId, List<ReservationLine> lines, String correlationId) {
        var existing = reservations.findByOrderId(orderId);
        if (existing.isPresent()) {
            return new Result.Created(existing.get());
        }

        Reservation reservation = Reservation.create(identifiers.get(), orderId, lines, correlationId, ttl,
                Instant.now(clock));
        if (!reservations.tryCreate(reservation)) {
            return new Result.Created(reservations.findByOrderId(orderId).orElseThrow());
        }

        List<String> skuCodes = lines.stream().map(ReservationLine::skuCode).sorted().toList();
        Map<String, StockItem> locked = stock.findAllForUpdateOrdered(skuCodes).stream()
                .collect(Collectors.toMap(StockItem::skuCode, Function.identity()));

        if (locked.size() != skuCodes.size()) {
            reservations.discardClaim(reservation.id());
            return new Result.UnknownStockItem();
        }

        for (ReservationLine line : lines) {
            if (!locked.get(line.skuCode()).canReserve(line.quantity())) {
                reservations.discardClaim(reservation.id());
                return new Result.InsufficientStock(line.skuCode());
            }
        }

        lines.forEach(line -> locked.get(line.skuCode()).reserve(line.quantity()));
        stock.saveAll(skuCodes.stream().map(locked::get).toList());

        return new Result.Created(reservations.save(reservation));
    }

    public sealed interface Result {

        record Created(Reservation reservation) implements Result {
        }

        record UnknownStockItem() implements Result {
        }

        record InsufficientStock(String skuCode) implements Result {
        }
    }
}
