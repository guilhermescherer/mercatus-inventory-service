package com.mercatus.inventory.infrastructure.messaging.contract.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationFailedPayload(UUID orderId, Reason reason) {

    public enum Reason {
        OUT_OF_STOCK
    }
}
