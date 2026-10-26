package com.mercatus.inventory.infrastructure.persistence.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock_item")
class StockItemEntity {

    @Id
    @Column(name = "sku_code")
    private String skuCode;

    @Column(name = "on_hand", nullable = false)
    private long onHand;

    @Column(nullable = false)
    private long reserved;

    @Column(nullable = false)
    private long committed;

    @Version
    @Column(nullable = false)
    private Long version;

    protected StockItemEntity() {
    }

    String getSkuCode() {
        return skuCode;
    }

    void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    long getOnHand() {
        return onHand;
    }

    void setOnHand(long onHand) {
        this.onHand = onHand;
    }

    long getReserved() {
        return reserved;
    }

    void setReserved(long reserved) {
        this.reserved = reserved;
    }

    long getCommitted() {
        return committed;
    }

    void setCommitted(long committed) {
        this.committed = committed;
    }

    Long getVersion() {
        return version;
    }

    void setVersion(Long version) {
        this.version = version;
    }
}
