package com.mercatus.inventory.infrastructure.persistence.stock;

import com.mercatus.inventory.application.stock.port.out.StockRepository;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
class StockRepositoryAdapter implements StockRepository {

    private final StockItemJpaRepository jpaRepository;

    StockRepositoryAdapter(StockItemJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void initializeIfAbsent(String skuCode) {
        jpaRepository.insertIfAbsent(skuCode);
    }

    @Override
    public StockItem save(StockItem stockItem) {
        return StockItemMapper.toDomain(jpaRepository.save(StockItemMapper.toEntity(stockItem)));
    }

    @Override
    public List<StockItem> saveAll(List<StockItem> stockItems) {
        List<StockItemEntity> entities = stockItems.stream().map(StockItemMapper::toEntity).toList();
        return jpaRepository.saveAll(entities).stream().map(StockItemMapper::toDomain).toList();
    }

    @Override
    public Optional<StockItem> findBySkuCode(String skuCode) {
        return jpaRepository.findById(skuCode).map(StockItemMapper::toDomain);
    }

    @Override
    public List<StockItem> findAllForUpdateOrdered(Collection<String> skuCodes) {
        return jpaRepository.findAllForUpdateOrdered(skuCodes).stream()
                .map(StockItemMapper::toDomain)
                .toList();
    }
}
