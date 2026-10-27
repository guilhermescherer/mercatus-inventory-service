package com.mercatus.inventory.application.reservation.port.in;

import java.util.List;
import java.util.UUID;

public interface ReserveUseCase {

    UUID reserve(ReserveCommand command);

    record ReserveCommand(UUID orderId, List<Line> lines) {

        public record Line(String skuCode, long quantity) {
        }
    }
}
