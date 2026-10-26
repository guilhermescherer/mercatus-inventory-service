package com.mercatus.inventory.web.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReserveRequest(
        @NotNull UUID orderId,
        @NotEmpty List<@NotNull @Valid ReservationLineRequest> lines) {
}
