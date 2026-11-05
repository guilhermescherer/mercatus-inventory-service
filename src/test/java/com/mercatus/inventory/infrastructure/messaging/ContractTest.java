package com.mercatus.inventory.infrastructure.messaging;

import com.mercatus.inventory.infrastructure.messaging.payload.InventoryReservedPayload;
import com.mercatus.inventory.infrastructure.messaging.payload.ReserveInventoryPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

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

    @Test
    void readEnvelopeAcceptsCompleteMessage() {
        UUID messageId = UUID.randomUUID();
        String json = """
                {
                  "messageId": "%s",
                  "type": "inventory.Example",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-27T20:00:00Z",
                  "aggregateId": "order-1",
                  "correlationId": "corr-1",
                  "causationId": null,
                  "payload": { "orderId": "order-1" },
                  "extra": "ignored"
                }
                """.formatted(messageId);

        var envelope = reader.readEnvelope(json);

        assertThat(envelope.messageId()).isEqualTo(messageId);
        assertThat(envelope.payload().get("orderId").asString()).isEqualTo("order-1");
    }

    @Test
    void readEnvelopeReportsMissingFieldAsContractViolation() {
        String json = """
                { "type": "inventory.Example", "schemaVersion": 1, "occurredAt": "2026-10-27T20:00:00Z",
                  "aggregateId": "order-1", "correlationId": "corr-1", "payload": {} }
                """;

        assertThatThrownBy(() -> reader.readEnvelope(json))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("messageId is required");
    }

    @Test
    void readEnvelopeRejectsMalformedJson() {
        assertThatThrownBy(() -> reader.readEnvelope("not-json"))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> reader.readEnvelope(" "))
                .isInstanceOf(ContractViolationException.class);
    }
}
