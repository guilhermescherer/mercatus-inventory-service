package com.mercatus.inventory.infrastructure.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservedPayload(UUID orderId, UUID reservationId, Instant expiresAt) {

    public static final String TYPE = "inventory.InventoryReserved";
}
