package com.mercatus.inventory.application.stock.port.in;

public interface ReceiveStockUseCase {

    void receive(ReceiveStockCommand command);

    record ReceiveStockCommand(String skuCode, long quantity) {
    }
}
