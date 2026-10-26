package com.mercatus.inventory.infrastructure.persistence.stock;

import com.mercatus.inventory.domain.stock.StockMovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "stock_movement")
class StockMovementEntity {

    @Id
    private UUID id;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockMovementType type;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column
    private String reason;

    @Column(name = "reservation_id")
    private UUID reservationId;

    protected StockMovementEntity() {
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    void setType(StockMovementType type) {
        this.type = type;
    }

    void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    void setReason(String reason) {
        this.reason = reason;
    }

    void setReservationId(UUID reservationId) {
        this.reservationId = reservationId;
    }
}
