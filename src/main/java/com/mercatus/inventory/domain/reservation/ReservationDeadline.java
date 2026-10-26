package com.mercatus.inventory.domain.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationDeadline(UUID reservationId, Instant expiresAt) {
}
