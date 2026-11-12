package com.mercatus.inventory.infrastructure.messaging.contract;

import com.mercatus.inventory.infrastructure.messaging.contract.payload.InventoryReservedPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReservationExpiredPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReservationReleasedPayload;
import com.mercatus.inventory.infrastructure.messaging.contract.payload.ReserveInventoryPayload;
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
    void releasePayloadsRoundTrip() throws Exception {
        var released = new ReservationReleasedPayload(UUID.randomUUID(), UUID.randomUUID());
        var expired = new ReservationExpiredPayload(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-06T20:30:00Z"));

        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(released),
                ReservationReleasedPayload.class)).isEqualTo(released);
        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(expired),
                ReservationExpiredPayload.class)).isEqualTo(expired);
    }

    @Test
    void readReleaseReservationToleratesAdditiveFields() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String json = """
                { "orderId": "%s", "reservationId": "%s", "extra": true }
                """.formatted(orderId, reservationId);

        var read = reader.readReleaseReservation(objectMapper.readTree(json));

        assertThat(read.orderId()).isEqualTo(orderId);
        assertThat(read.reservationId()).isEqualTo(reservationId);
    }

    @Test
    void readReleaseReservationRequiresReservationId() throws Exception {
        String json = """
                { "orderId": "%s" }
                """.formatted(UUID.randomUUID());

        assertThatThrownBy(() -> reader.readReleaseReservation(objectMapper.readTree(json)))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("reservationId is required");
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
