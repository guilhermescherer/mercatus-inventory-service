package com.mercatus.inventory.application.reservation.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReserveForOrderUseCase {

    Outcome reserve(ReserveForOrderCommand command);

    record ReserveForOrderCommand(UUID orderId, List<Line> lines) {

        public record Line(String skuCode, long quantity) {
        }
    }

    sealed interface Outcome {

        record Reserved(UUID reservationId, Instant expiresAt) implements Outcome {
        }

        record OutOfStock() implements Outcome {
        }
    }
}
