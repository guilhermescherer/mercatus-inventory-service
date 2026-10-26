package com.mercatus.inventory.web.stock.dto;

public record StockResponse(String skuCode, long onHand, long reserved, long committed, long available) {
}
