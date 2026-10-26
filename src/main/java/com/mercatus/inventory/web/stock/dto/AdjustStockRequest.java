package com.mercatus.inventory.web.stock.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdjustStockRequest(@NotNull Long delta, @NotBlank String reason) {

    @AssertTrue(message = "Delta must not be zero")
    @Schema(hidden = true)
    public boolean isDeltaNonZero() {
        return delta == null || delta != 0;
    }
}
