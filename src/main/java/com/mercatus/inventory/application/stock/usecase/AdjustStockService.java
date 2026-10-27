package com.mercatus.inventory.application.stock.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.stock.port.in.AdjustStockUseCase;
import com.mercatus.inventory.application.stock.port.out.StockRepository;
import com.mercatus.inventory.application.stock.port.out.StockMovementRepository;
import com.mercatus.inventory.domain.stock.StockMovement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AdjustStockService implements AdjustStockUseCase {

    private final StockRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public AdjustStockService(StockRepository stockRepository,
                              StockMovementRepository movementRepository, Clock clock,
                              Supplier<UUID> identifiers) {
        this.stockRepository = stockRepository;
        this.movementRepository = movementRepository;
        this.clock = clock;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional
    public void adjust(AdjustStockCommand command) {
        String skuCode = command.skuCode();
        long delta = command.delta();
        String reason = command.reason();

        var movement = StockMovement.adjustment(identifiers.get(), skuCode, delta, reason, Instant.now(clock));
        var items = stockRepository.findAllForUpdateOrdered(List.of(skuCode));
        if (items.isEmpty()) {
            throw new ResourceNotFoundException("Stock item not found: " + skuCode);
        }
        var item = items.getFirst();
        item.adjust(delta);
        stockRepository.save(item);
        movementRepository.append(movement);
    }
}
