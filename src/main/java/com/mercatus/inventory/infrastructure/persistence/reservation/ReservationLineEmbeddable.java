package com.mercatus.inventory.infrastructure.persistence.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class ReservationLineEmbeddable {

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(nullable = false)
    private long quantity;

    protected ReservationLineEmbeddable() {
    }

    ReservationLineEmbeddable(String skuCode, long quantity) {
        this.skuCode = skuCode;
        this.quantity = quantity;
    }

    String getSkuCode() {
        return skuCode;
    }

    long getQuantity() {
        return quantity;
    }
}
