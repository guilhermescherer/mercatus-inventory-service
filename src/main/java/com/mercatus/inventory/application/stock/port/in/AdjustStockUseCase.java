package com.mercatus.inventory.application.stock.port.in;

public interface AdjustStockUseCase {

    void adjust(String skuCode, long delta, String reason);
}
