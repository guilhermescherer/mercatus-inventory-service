package com.mercatus.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercatus.inventory.infrastructure.messaging.payload.ReserveInventoryPayload;

public final class ContractReader {

    private final ObjectMapper objectMapper;

    public ContractReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReserveInventoryPayload readReserveInventory(JsonNode payload) {
        var value = convert(payload, ReserveInventoryPayload.class);

        require(value.orderId(), "orderId");

        if (value.lines() == null || value.lines().isEmpty()) {
            throw new ContractViolationException("lines are required");
        }

        for (var line : value.lines()) {
            requireText(line.skuCode(), "skuCode");

            if (line.quantity() <= 0) {
                throw new ContractViolationException("quantity must be positive");
            }
        }

        return value;
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ContractViolationException("Payload is incompatible with " + type.getSimpleName(), e);
        }
    }

    private void require(Object value, String field) {
        if (value == null) {
            throw new ContractViolationException(field + " is required");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ContractViolationException(field + " is required");
        }
    }
}
