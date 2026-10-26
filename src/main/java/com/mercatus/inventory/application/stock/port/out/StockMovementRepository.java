package com.mercatus.inventory.application.stock.port.out;

import com.mercatus.inventory.domain.stock.StockMovement;

public interface StockMovementRepository {

    void append(StockMovement movement);
}
