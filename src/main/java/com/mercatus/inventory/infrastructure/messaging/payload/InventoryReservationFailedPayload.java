package com.mercatus.inventory.infrastructure.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationFailedPayload(UUID orderId, String reason) {

    public static final String TYPE = "inventory.InventoryReservationFailed";

    public static final String OUT_OF_STOCK = "OUT_OF_STOCK";
}
