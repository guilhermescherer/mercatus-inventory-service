package com.mercatus.inventory.domain.stock;

import com.mercatus.inventory.domain.exception.DomainRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockMovementTest {

    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void receiptsAndShipmentsHaveOppositeSignsAndShipmentReferencesReservation() {
        UUID reservation = UUID.randomUUID();
        var receipt = StockMovement.receipt("SKU", 3, now);
        var shipment = StockMovement.shipment("SKU", 2, reservation, now);
        assertThat(receipt.quantity()).isEqualTo(3);
        assertThat(receipt.reservationId()).isNull();
        assertThat(shipment.quantity()).isEqualTo(-2);
        assertThat(shipment.reservationId()).isEqualTo(reservation);
        assertThat(shipment.occurredAt()).isEqualTo(now);
    }

    @Test
    void adjustmentsRequireNonZeroQuantityAndMeaningfulReason() {
        assertThat(StockMovement.adjustment("SKU", -3, "Damage", now).quantity()).isEqualTo(-3);
        assertThat(StockMovement.adjustment("SKU", 3, "Count", now).quantity()).isEqualTo(3);
        assertThatThrownBy(() -> StockMovement.adjustment("SKU", 0, "Count", now))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> StockMovement.adjustment("SKU", 1, " ", now))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void factoriesRejectInvalidSignsAndMissingShipmentReservation() {
        assertThatThrownBy(() -> StockMovement.receipt("SKU", -1, now))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> StockMovement.shipment("SKU", -1, UUID.randomUUID(), now))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> StockMovement.shipment("SKU", 1, null, now))
                .isInstanceOf(DomainRuleException.class);
    }
}
