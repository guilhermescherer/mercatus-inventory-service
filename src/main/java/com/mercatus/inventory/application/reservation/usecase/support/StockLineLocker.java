package com.mercatus.inventory.application.reservation.usecase.support;

import com.mercatus.inventory.application.exception.ResourceNotFoundException;
import com.mercatus.inventory.application.stock.port.out.StockRepository;
import com.mercatus.inventory.domain.reservation.ReservationLine;
import com.mercatus.inventory.domain.stock.StockItem;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Component
public class StockLineLocker {

    private final StockRepository stockRepository;

    public StockLineLocker(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public void lockApplyAndSave(List<ReservationLine> lines, BiConsumer<StockItem, Long> effect) {
        List<String> skuCodes = lines.stream()
                .map(ReservationLine::skuCode)
                .sorted()
                .toList();

        List<StockItem> locked = stockRepository.findAllForUpdateOrdered(skuCodes);
        if (locked.size() != skuCodes.size()) {
            throw new ResourceNotFoundException("One or more stock items were not found");
        }

        Map<String, StockItem> bySku = new LinkedHashMap<>();
        locked.forEach(item -> bySku.put(item.skuCode(), item));

        lines.forEach(line -> effect.accept(bySku.get(line.skuCode()), line.quantity()));

        stockRepository.saveAll(List.copyOf(bySku.values()));
    }
}
