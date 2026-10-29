package com.mercatus.inventory.infrastructure.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveInventoryPayload(UUID orderId, List<Line> lines) {

    public static final String TYPE = "inventory.ReserveInventory";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String skuCode, long quantity) {
    }
}
