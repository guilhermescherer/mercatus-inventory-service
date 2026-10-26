package com.mercatus.inventory.application.reservation.port.in;

import java.util.List;
import java.util.UUID;

public interface ReserveUseCase {

    UUID reserve(UUID orderId, List<Line> lines);

    record Line(String skuCode, long quantity) {
    }
}
