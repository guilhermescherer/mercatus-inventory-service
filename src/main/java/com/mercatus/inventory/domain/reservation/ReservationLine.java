package com.mercatus.inventory.domain.reservation;

import com.mercatus.inventory.domain.exception.DomainRuleException;

public record ReservationLine(String skuCode, long quantity) {

    public ReservationLine {
        if (skuCode == null || skuCode.isBlank()) {
            throw new DomainRuleException("Reservation line SKU code must not be blank");
        }
        if (quantity <= 0) {
            throw new DomainRuleException("Reservation line quantity must be positive");
        }
    }
}
