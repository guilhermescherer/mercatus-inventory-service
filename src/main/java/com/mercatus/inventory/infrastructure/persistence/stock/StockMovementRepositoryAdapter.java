package com.mercatus.inventory.infrastructure.persistence.stock;

import com.mercatus.inventory.application.stock.port.out.StockMovementRepository;
import com.mercatus.inventory.domain.stock.StockMovement;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
class StockMovementRepositoryAdapter implements StockMovementRepository {

    private final EntityManager entityManager;

    StockMovementRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void append(StockMovement movement) {
        entityManager.persist(StockMovementMapper.toEntity(movement));
    }
}
