package com.mercatus.inventory.application.stock.usecase;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.stock.port.in.GetStockUseCase;
import com.mercatus.inventory.application.stock.port.out.StockRepository;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetStockService implements GetStockUseCase {

    private final StockRepository stockRepository;

    public GetStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public StockView get(String skuCode) {
        StockItem item = stockRepository.findBySkuCode(skuCode)
                .orElseThrow(() -> new ResourceNotFoundException("Stock item not found for SKU " + skuCode));
        return new StockView(item.skuCode(), item.onHand(), item.reserved(), item.committed(), item.available());
    }
}
