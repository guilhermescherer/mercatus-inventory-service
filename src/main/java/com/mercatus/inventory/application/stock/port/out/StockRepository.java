package com.mercatus.inventory.application.stock.port.out;

import com.mercatus.inventory.domain.stock.StockItem;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository {

    void initializeIfAbsent(String skuCode);

    StockItem save(StockItem stockItem);

    List<StockItem> saveAll(List<StockItem> stockItems);

    Optional<StockItem> findBySkuCode(String skuCode);

    List<StockItem> findAllForUpdateOrdered(Collection<String> skuCodes);
}
