package com.mercatus.inventory.infrastructure.persistence.stock;

import com.mercatus.inventory.domain.stock.StockMovement;

final class StockMovementMapper {

    private StockMovementMapper() {
    }

    static StockMovementEntity toEntity(StockMovement movement) {
        StockMovementEntity entity = new StockMovementEntity();
        entity.setId(movement.id());
        entity.setSkuCode(movement.skuCode());
        entity.setType(movement.type());
        entity.setQuantity(movement.quantity());
        entity.setOccurredAt(movement.occurredAt());
        entity.setReason(movement.reason());
        entity.setReservationId(movement.reservationId());
        return entity;
    }
}
