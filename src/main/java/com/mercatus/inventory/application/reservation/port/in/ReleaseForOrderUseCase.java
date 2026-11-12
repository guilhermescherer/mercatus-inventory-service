package com.mercatus.inventory.application.reservation.port.in;

import java.time.Instant;
import java.util.UUID;

public interface ReleaseForOrderUseCase {

    Outcome release(UUID orderId, UUID reservationId);

    sealed interface Outcome {

        record Released(UUID reservationId) implements Outcome {
        }

        record AlreadyExpired(UUID reservationId, Instant expiresAt) implements Outcome {
        }

        record Rejected(String reason) implements Outcome {
        }
    }
}
