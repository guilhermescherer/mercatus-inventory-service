package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.reservation.port.in.ReserveForOrderUseCase;
import com.mercatus.inventory.application.reservation.usecase.support.ReservationCreator;
import com.mercatus.inventory.application.reservation.usecase.support.ReservationCreator.Result;
import com.mercatus.inventory.domain.reservation.ReservationLine;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReserveForOrderService implements ReserveForOrderUseCase {

    private final ReservationCreator creator;

    public ReserveForOrderService(ReservationCreator creator) {
        this.creator = creator;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome reserve(ReserveForOrderCommand command) {
        Map<String, Long> quantities = command.lines().stream()
                .collect(Collectors.toMap(ReserveForOrderCommand.Line::skuCode,
                        ReserveForOrderCommand.Line::quantity, Long::sum, TreeMap::new));
        List<ReservationLine> lines = quantities.entrySet().stream()
                .map(line -> new ReservationLine(line.getKey(), line.getValue()))
                .toList();

        return switch (creator.create(command.orderId(), lines, command.correlationId())) {
            case Result.Created created ->
                    new Outcome.Reserved(created.reservation().id(), created.reservation().expiresAt());
            case Result.UnknownStockItem unknown -> new Outcome.OutOfStock();
            case Result.InsufficientStock insufficient -> new Outcome.OutOfStock();
        };
    }
}
