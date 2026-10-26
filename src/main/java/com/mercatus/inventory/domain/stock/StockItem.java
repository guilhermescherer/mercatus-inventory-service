package com.mercatus.inventory.domain.stock;

import com.mercatus.inventory.domain.exception.DomainRuleException;

public class StockItem {

    private final String skuCode;
    private long onHand;
    private long reserved;
    private long committed;
    private Long version;

    private StockItem(String skuCode, long onHand, long reserved, long committed, Long version) {
        this.skuCode = skuCode;
        this.onHand = onHand;
        this.reserved = reserved;
        this.committed = committed;
        this.version = version;
    }

    public static StockItem create(String skuCode) {
        requireSkuCode(skuCode);
        return new StockItem(skuCode, 0, 0, 0, null);
    }

    public static StockItem reconstitute(String skuCode, long onHand, long reserved, long committed, Long version) {
        return new StockItem(skuCode, onHand, reserved, committed, version);
    }

    public long available() {
        return onHand - reserved - committed;
    }

    public void receive(long quantity) {
        requirePositive(quantity);
        this.onHand = add(onHand, quantity);
    }

    public void adjust(long delta) {
        if (delta == 0) {
            throw new DomainRuleException("Adjustment must not be zero");
        }
        long adjusted = add(onHand, delta);
        if (adjusted < 0) {
            throw new DomainRuleException("Adjustment would make onHand negative for SKU " + skuCode);
        }
        if (adjusted - reserved - committed < 0) {
            throw new DomainRuleException("Adjustment would make available negative for SKU " + skuCode);
        }
        this.onHand = adjusted;
    }

    public void reserve(long quantity) {
        requirePositive(quantity);
        if (available() < quantity) {
            throw new DomainRuleException("Insufficient stock for SKU " + skuCode);
        }
        this.reserved += quantity;
    }

    public void confirmReservation(long quantity) {
        requirePositive(quantity);
        if (reserved < quantity) {
            throw new DomainRuleException("Cannot confirm more than reserved for SKU " + skuCode);
        }
        this.reserved -= quantity;
        this.committed += quantity;
    }

    public void releaseReserved(long quantity) {
        requirePositive(quantity);
        if (reserved < quantity) {
            throw new DomainRuleException("Cannot release more than reserved for SKU " + skuCode);
        }
        this.reserved -= quantity;
    }

    public void releaseCommitted(long quantity) {
        requirePositive(quantity);
        if (committed < quantity) {
            throw new DomainRuleException("Cannot release more than committed for SKU " + skuCode);
        }
        this.committed -= quantity;
    }

    public void consume(long quantity) {
        requirePositive(quantity);
        if (committed < quantity || onHand < quantity) {
            throw new DomainRuleException("Cannot consume more than committed/onHand for SKU " + skuCode);
        }
        this.committed -= quantity;
        this.onHand -= quantity;
    }

    private static long add(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException ex) {
            throw new DomainRuleException("Stock quantity exceeds supported range");
        }
    }

    private static void requireSkuCode(String skuCode) {
        if (skuCode == null || skuCode.isBlank()) {
            throw new DomainRuleException("SKU code must not be blank");
        }
    }

    private static void requirePositive(long quantity) {
        if (quantity <= 0) {
            throw new DomainRuleException("Quantity must be positive");
        }
    }

    public String skuCode() {
        return skuCode;
    }

    public long onHand() {
        return onHand;
    }

    public long reserved() {
        return reserved;
    }

    public long committed() {
        return committed;
    }

    public Long version() {
        return version;
    }
}
