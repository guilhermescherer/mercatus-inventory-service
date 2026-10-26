package com.mercatus.inventory.web.stock.dto;

import jakarta.validation.constraints.Positive;

public record ReceiveStockRequest(@Positive long quantity) {
}
