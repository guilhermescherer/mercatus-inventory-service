package com.mercatus.inventory.application.stock.port.in;

public interface ReceiveStockUseCase {

    void receive(String skuCode, long quantity);
}
