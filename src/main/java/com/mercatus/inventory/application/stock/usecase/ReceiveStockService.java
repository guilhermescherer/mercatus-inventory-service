package com.mercatus.inventory.application.stock.usecase;

import com.mercatus.inventory.application.stock.port.in.ReceiveStockUseCase;
import com.mercatus.inventory.application.stock.port.out.StockRepository;
import com.mercatus.inventory.application.stock.port.out.StockMovementRepository;
import com.mercatus.inventory.domain.stock.StockItem;
import com.mercatus.inventory.domain.stock.StockMovement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ReceiveStockService implements ReceiveStockUseCase {

    private final StockRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ReceiveStockService(StockRepository stockRepository,
                               StockMovementRepository movementRepository, Clock clock,
                               Supplier<UUID> identifiers) {
        this.stockRepository = stockRepository;
        this.movementRepository = movementRepository;
        this.clock = clock;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional
    public void receive(ReceiveStockCommand command) {
        String skuCode = command.skuCode();
        long quantity = command.quantity();

        var movement = StockMovement.receipt(identifiers.get(), skuCode, quantity, Instant.now(clock));
        stockRepository.initializeIfAbsent(skuCode);
        StockItem item = stockRepository.findAllForUpdateOrdered(List.of(skuCode)).getFirst();
        item.receive(quantity);
        stockRepository.save(item);
        movementRepository.append(movement);
    }
}
