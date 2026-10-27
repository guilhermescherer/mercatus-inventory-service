package com.mercatus.inventory.application.stock.port.in;

public interface AdjustStockUseCase {

    void adjust(AdjustStockCommand command);

    record AdjustStockCommand(String skuCode, long delta, String reason) {
    }
}
