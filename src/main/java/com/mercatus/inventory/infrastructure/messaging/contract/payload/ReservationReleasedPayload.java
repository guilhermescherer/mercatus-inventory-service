package com.mercatus.inventory.infrastructure.messaging.contract.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationReleasedPayload(UUID orderId, UUID reservationId) {
}
