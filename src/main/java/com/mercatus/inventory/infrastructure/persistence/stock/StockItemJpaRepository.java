package com.mercatus.inventory.infrastructure.persistence.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface StockItemJpaRepository extends JpaRepository<StockItemEntity, String> {

    @Modifying
    @Query(value = """
            insert into stock_item (sku_code, on_hand, reserved, committed, version)
            values (:skuCode, 0, 0, 0, 0) on conflict (sku_code) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("skuCode") String skuCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from StockItemEntity s
             where s.skuCode in :skuCodes
             order by s.skuCode
            """)
    List<StockItemEntity> findAllForUpdateOrdered(@Param("skuCodes") Collection<String> skuCodes);
}
