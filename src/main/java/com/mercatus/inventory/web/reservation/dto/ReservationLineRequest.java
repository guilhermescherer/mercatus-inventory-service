package com.mercatus.inventory.web.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ReservationLineRequest(@NotBlank String skuCode, @Positive long quantity) {
}
