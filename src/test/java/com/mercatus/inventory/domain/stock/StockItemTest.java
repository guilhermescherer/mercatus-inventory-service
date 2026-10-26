package com.mercatus.inventory.domain.stock;

import com.mercatus.inventory.domain.exception.DomainRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockItemTest {

    @Test
    void availableIsOnHandMinusReservedAndCommitted() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 3, 2, 0L);
        assertThat(item.available()).isEqualTo(5);
    }

    @Test
    void receiveIncreasesOnHand() {
        StockItem item = StockItem.create("SKU-1");
        item.receive(5);
        assertThat(item.onHand()).isEqualTo(5);
        assertThat(item.available()).isEqualTo(5);
    }

    @Test
    void reserveReducesAvailable() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 0, 0, 0L);
        item.reserve(4);
        assertThat(item.reserved()).isEqualTo(4);
        assertThat(item.available()).isEqualTo(6);
    }

    @Test
    void reserveBeyondAvailableIsRejected() {
        StockItem item = StockItem.reconstitute("SKU-1", 1, 0, 0, 0L);
        assertThatThrownBy(() -> item.reserve(2)).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void negativeAdjustmentCannotMakeOnHandNegative() {
        StockItem item = StockItem.reconstitute("SKU-1", 5, 0, 0, 0L);
        assertThatThrownBy(() -> item.adjust(-6)).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void nonPositiveReserveIsRejected() {
        StockItem item = StockItem.reconstitute("SKU-1", 5, 0, 0, 0L);
        assertThatThrownBy(() -> item.reserve(0)).isInstanceOf(DomainRuleException.class);
    }
    @Test
    void overflowingReceiptAndAdjustmentLeaveStockUnchanged() {
        var stock = StockItem.create("SKU");
        stock.receive(Long.MAX_VALUE);
        assertThatThrownBy(() -> stock.receive(1)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> stock.adjust(1)).isInstanceOf(DomainRuleException.class);
        assertThat(stock.onHand()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> stock.adjust(Long.MIN_VALUE)).isInstanceOf(DomainRuleException.class);
        assertThat(stock.onHand()).isEqualTo(Long.MAX_VALUE);
    }
    @Test
    void negativeAdjustmentCannotConsumeReservedOrCommittedStock() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 4, 3, 0L);
        assertThatThrownBy(() -> item.adjust(-4)).isInstanceOf(DomainRuleException.class);
        assertThat(item.onHand()).isEqualTo(10);
        assertThat(item.available()).isEqualTo(3);
    }

    @Test
    void confirmingMovesReservedToCommittedWithoutChangingAvailability() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 4, 2, 0L);
        item.confirmReservation(3);
        assertThat(item.reserved()).isEqualTo(1);
        assertThat(item.committed()).isEqualTo(5);
        assertThat(item.onHand()).isEqualTo(10);
        assertThat(item.available()).isEqualTo(4);
    }

    @Test
    void consumingDecreasesPhysicalAndCommittedQuantitiesTogether() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 2, 4, 0L);
        item.consume(3);
        assertThat(item.onHand()).isEqualTo(7);
        assertThat(item.committed()).isEqualTo(1);
        assertThat(item.reserved()).isEqualTo(2);
        assertThat(item.available()).isEqualTo(4);
    }

    @Test
    void releasingReservedStockKeepsCommittedAndPhysicalQuantities() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 4, 2, 0L);
        item.releaseReserved(3);
        assertThat(item.reserved()).isEqualTo(1);
        assertThat(item.committed()).isEqualTo(2);
        assertThat(item.onHand()).isEqualTo(10);
        assertThat(item.available()).isEqualTo(7);
    }

    @Test
    void releasingCommittedStockKeepsReservedAndPhysicalQuantities() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 2, 4, 0L);
        item.releaseCommitted(3);
        assertThat(item.committed()).isEqualTo(1);
        assertThat(item.reserved()).isEqualTo(2);
        assertThat(item.onHand()).isEqualTo(10);
        assertThat(item.available()).isEqualTo(7);
    }

    @Test
    void invalidTransfersLeaveAllQuantitiesUnchanged() {
        StockItem item = StockItem.reconstitute("SKU-1", 10, 2, 3, 0L);
        assertThatThrownBy(() -> item.confirmReservation(3)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> item.releaseReserved(3)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> item.releaseCommitted(4)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> item.consume(4)).isInstanceOf(DomainRuleException.class);
        assertThat(item.onHand()).isEqualTo(10);
        assertThat(item.reserved()).isEqualTo(2);
        assertThat(item.committed()).isEqualTo(3);
    }
}
