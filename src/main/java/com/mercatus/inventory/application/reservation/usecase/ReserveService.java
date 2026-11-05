package com.mercatus.inventory.application.reservation.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.reservation.port.in.ReserveUseCase;
import com.mercatus.inventory.application.reservation.usecase.support.ReservationCreator;
import com.mercatus.inventory.application.reservation.usecase.support.ReservationCreator.Result;
import com.mercatus.inventory.domain.exception.DomainRuleException;
import com.mercatus.inventory.domain.reservation.ReservationLine;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReserveService implements ReserveUseCase {

    private final ReservationCreator creator;

    public ReserveService(ReservationCreator creator) {
        this.creator = creator;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID reserve(ReserveCommand command) {
        var lines = command.lines().stream()
                .map(line -> new ReservationLine(line.skuCode(), line.quantity()))
                .toList();

        return switch (creator.create(command.orderId(), lines)) {
            case Result.Created created -> created.reservation().id();
            case Result.UnknownStockItem unknown ->
                    throw new ResourceNotFoundException("One or more stock items were not found");
            case Result.InsufficientStock insufficient ->
                    throw new DomainRuleException("Insufficient stock for SKU " + insufficient.skuCode());
        };
    }
}
