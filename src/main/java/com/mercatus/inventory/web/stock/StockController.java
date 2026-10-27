package com.mercatus.inventory.web.stock;

import com.mercatus.inventory.application.stock.port.in.GetStockUseCase;
import com.mercatus.inventory.application.stock.port.in.AdjustStockUseCase;
import com.mercatus.inventory.web.stock.dto.AdjustStockRequest;
import com.mercatus.inventory.application.stock.port.in.ReceiveStockUseCase;
import com.mercatus.inventory.web.stock.dto.ReceiveStockRequest;
import com.mercatus.inventory.web.stock.dto.StockResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stock")
public class StockController {

    private final ReceiveStockUseCase receiveStock;
    private final GetStockUseCase getStock;
    private final AdjustStockUseCase adjustStock;

    public StockController(ReceiveStockUseCase receiveStock, GetStockUseCase getStock,
                           AdjustStockUseCase adjustStock) {
        this.adjustStock = adjustStock;
        this.receiveStock = receiveStock;
        this.getStock = getStock;
    }

    @PostMapping("/{skuCode}/receipts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@PathVariable String skuCode, @Valid @RequestBody ReceiveStockRequest request) {
        receiveStock.receive(new ReceiveStockUseCase.ReceiveStockCommand(skuCode, request.quantity()));
    }

    @PostMapping("/{skuCode}/adjustments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adjust(@PathVariable String skuCode, @Valid @RequestBody AdjustStockRequest request) {
        adjustStock.adjust(new AdjustStockUseCase.AdjustStockCommand(skuCode, request.delta(), request.reason()));
    }

    @GetMapping("/{skuCode}")
    public StockResponse get(@PathVariable String skuCode) {
        var view = getStock.get(skuCode);
        return new StockResponse(view.skuCode(), view.onHand(), view.reserved(),
                view.committed(), view.available());
    }
}
