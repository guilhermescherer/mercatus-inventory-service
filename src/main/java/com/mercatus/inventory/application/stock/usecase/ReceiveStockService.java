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

@Service
public class ReceiveStockService implements ReceiveStockUseCase {

    private final StockRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final Clock clock;

    public ReceiveStockService(StockRepository stockRepository,
                               StockMovementRepository movementRepository, Clock clock) {
        this.stockRepository = stockRepository;
        this.movementRepository = movementRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void receive(String skuCode, long quantity) {
        var movement = StockMovement.receipt(skuCode, quantity, Instant.now(clock));
        stockRepository.initializeIfAbsent(skuCode);
        StockItem item = stockRepository.findAllForUpdateOrdered(List.of(skuCode)).getFirst();
        item.receive(quantity);
        stockRepository.save(item);
        movementRepository.append(movement);
    }
}
