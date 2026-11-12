package com.mercatus.inventory.infrastructure.messaging.contract;

public enum OutboundMessageType {

    INVENTORY_RESERVED("inventory.InventoryReserved", 1),
    INVENTORY_RESERVATION_FAILED("inventory.InventoryReservationFailed", 1),
    RESERVATION_RELEASED("inventory.ReservationReleased", 1),
    RESERVATION_EXPIRED("inventory.ReservationExpired", 1);

    private final String value;
    private final int schemaVersion;

    OutboundMessageType(String value, int schemaVersion) {
        this.value = value;
        this.schemaVersion = schemaVersion;
    }

    public String value() {
        return value;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
