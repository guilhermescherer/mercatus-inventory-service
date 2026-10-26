package com.mercatus.inventory.infrastructure.persistence.stock;

import com.mercatus.inventory.domain.stock.StockItem;

final class StockItemMapper {

    private StockItemMapper() {
    }

    static StockItemEntity toEntity(StockItem stockItem) {
        StockItemEntity entity = new StockItemEntity();
        entity.setSkuCode(stockItem.skuCode());
        entity.setOnHand(stockItem.onHand());
        entity.setReserved(stockItem.reserved());
        entity.setCommitted(stockItem.committed());
        entity.setVersion(stockItem.version());
        return entity;
    }

    static StockItem toDomain(StockItemEntity entity) {
        return StockItem.reconstitute(
                entity.getSkuCode(),
                entity.getOnHand(),
                entity.getReserved(),
                entity.getCommitted(),
                entity.getVersion());
    }
}
