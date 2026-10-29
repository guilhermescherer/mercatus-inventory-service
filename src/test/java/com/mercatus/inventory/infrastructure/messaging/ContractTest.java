package com.mercatus.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mercatus.inventory.infrastructure.messaging.payload.InventoryReservedPayload;
import com.mercatus.inventory.infrastructure.messaging.payload.ReserveInventoryPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private final ContractReader reader = new ContractReader(objectMapper);

    @Test
    void reservedPayloadRoundTrip() throws Exception {
        var payload = new InventoryReservedPayload(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-10-27T20:30:00Z"));

        String json = objectMapper.writeValueAsString(payload);
        var back = objectMapper.readValue(json, InventoryReservedPayload.class);

        assertThat(back).isEqualTo(payload);
    }

    @Test
    void readReserveInventoryAcceptsCompletePayload() {
        UUID orderId = UUID.randomUUID();
        var payload = new ReserveInventoryPayload(orderId,
                List.of(new ReserveInventoryPayload.Line("SKU-1", 3)));

        var read = reader.readReserveInventory(objectMapper.valueToTree(payload));

        assertThat(read.orderId()).isEqualTo(orderId);
        assertThat(read.lines()).hasSize(1);
    }

    @Test
    void readReserveInventoryToleratesAdditiveFields() throws Exception {
        UUID orderId = UUID.randomUUID();
        String json = """
                { "orderId": "%s", "lines": [ { "skuCode": "SKU-1", "quantity": 2, "note": "ignored" } ], "extra": true }
                """.formatted(orderId);

        var read = reader.readReserveInventory(objectMapper.readTree(json));

        assertThat(read.orderId()).isEqualTo(orderId);
        assertThat(read.lines()).singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualTo(2));
    }

    @Test
    void readReserveInventoryRejectsEmptyLines() {
        UUID orderId = UUID.randomUUID();
        var payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId.toString());
        payload.putArray("lines");

        assertThatThrownBy(() -> reader.readReserveInventory(payload))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void readReserveInventoryRejectsNonPositiveQuantity() {
        UUID orderId = UUID.randomUUID();
        String json = """
                { "orderId": "%s", "lines": [ { "skuCode": "SKU-1", "quantity": 0 } ] }
                """.formatted(orderId);

        assertThatThrownBy(() -> reader.readReserveInventory(objectMapper.readTree(json)))
                .isInstanceOf(ContractViolationException.class);
    }
}
