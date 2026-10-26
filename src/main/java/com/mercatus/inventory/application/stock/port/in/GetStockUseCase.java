package com.mercatus.inventory.application.stock.port.in;

public interface GetStockUseCase {

    StockView get(String skuCode);

    record StockView(String skuCode, long onHand, long reserved, long committed, long available) {
    }
}
