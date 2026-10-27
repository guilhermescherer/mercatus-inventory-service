package com.mercatus.inventory.domain.stock;

import com.mercatus.inventory.domain.exception.DomainRuleException;

import java.time.Instant;
import java.util.UUID;

public record StockMovement(UUID id, String skuCode, StockMovementType type, long quantity,
                            Instant occurredAt, String reason, UUID reservationId) {

    public StockMovement {
        if (id == null || skuCode == null || skuCode.isBlank() || type == null || occurredAt == null) {
            throw new DomainRuleException("Movement identity, SKU, type and timestamp are required");
        }
        if (quantity == 0 || (type == StockMovementType.RECEIPT && quantity < 0)
                || (type == StockMovementType.SHIPMENT && quantity > 0)) {
            throw new DomainRuleException("Invalid movement quantity for type " + type);
        }
        if (type == StockMovementType.ADJUSTMENT && (reason == null || reason.isBlank())) {
            throw new DomainRuleException("Adjustment reason is required");
        }
        if ((type == StockMovementType.SHIPMENT && reservationId == null)
                || (type != StockMovementType.SHIPMENT && reservationId != null)) {
            throw new DomainRuleException("Only shipment movements must reference a reservation");
        }
    }

    public static StockMovement receipt(UUID id, String skuCode, long quantity, Instant now) {
        return new StockMovement(id, skuCode, StockMovementType.RECEIPT,
                quantity, now, null, null);
    }

    public static StockMovement adjustment(UUID id, String skuCode, long delta, String reason, Instant now) {
        return new StockMovement(id, skuCode, StockMovementType.ADJUSTMENT,
                delta, now, reason, null);
    }

    public static StockMovement shipment(UUID id, String skuCode, long quantity, UUID reservationId, Instant now) {
        if (quantity <= 0) {
            throw new DomainRuleException("Shipment quantity must be positive");
        }
        return new StockMovement(id, skuCode, StockMovementType.SHIPMENT,
                -quantity, now, null, reservationId);
    }
}
