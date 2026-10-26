package com.mercatus.inventory;

import com.mercatus.inventory.application.reservation.port.in.ConfirmReservationUseCase;
import com.mercatus.inventory.application.reservation.port.in.ReserveUseCase;
import com.mercatus.inventory.application.stock.port.in.ReceiveStockUseCase;
import com.mercatus.inventory.support.SqlCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.mercatus.inventory.support.SqlCapture",
        "inventory.reservation.expiry-interval-ms=3600000"
})
class ReservationLockIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    ReserveUseCase reserve;

    @Autowired
    ConfirmReservationUseCase confirm;

    @Autowired
    ReceiveStockUseCase receive;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reservationLockSqlDoesNotJoinCollectionAndStockLocksFollowSkuOrder() {
        String a = "A-" + UUID.randomUUID();
        String b = "B-" + UUID.randomUUID();
        receive.receive(a, 10);
        receive.receive(b, 10);
        UUID id = reserve.reserve(UUID.randomUUID(), List.of(new ReserveUseCase.Line(b, 2), new ReserveUseCase.Line(a, 3)));
        SqlCapture.clear();
        try {
            confirm.confirm(id);
            List<String> locks = SqlCapture.statements().stream()
                    .filter(sql -> sql.contains("for update") || sql.contains("for no key update"))
                    .toList();
            assertThat(locks).hasSize(2);
            assertThat(locks.getFirst()).contains("from reservation ").doesNotContain("join reservation_line");
            assertThat(locks.getLast()).contains("from stock_item", "order by", ".sku_code");
        } finally {
            SqlCapture.clear();
        }
    }

    @Test
    void competingCreatorCanSucceedAfterFirstCreatorRollsBack() throws Exception {
        String sku = "SKU-" + UUID.randomUUID();
        receive.receive(sku, 10);
        UUID order = UUID.randomUUID();
        var claimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contenderStarted = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                UUID id = reserve.reserve(order, List.of(new ReserveUseCase.Line(sku, 3)));
                claimed.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Rollback release timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                status.setRollbackOnly();
                return id;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                contenderStarted.countDown();
                return reserve.reserve(order, List.of(new ReserveUseCase.Line(sku, 2)));
            }));
            assertThat(contenderStarted.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("""
                            select exists (
                                select 1 from pg_stat_activity
                                 where pid = ? and ? = any(pg_blocking_pids(pid))
                                   and lower(query) like '%insert into reservation (%'
                            )
                            """, Boolean.class, secondPid.get(), firstPid.get())).isTrue());
            release.countDown();
            UUID rolledBack = first.get(15, TimeUnit.SECONDS);
            UUID committed = second.get(15, TimeUnit.SECONDS);
            assertThat(committed).isNotEqualTo(rolledBack);
            assertThat(jdbc.queryForObject("select count(*) from reservation where order_id = ?", Long.class, order))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select reserved from stock_item where sku_code = ?", Long.class, sku))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("select quantity from reservation_line where reservation_id = ?",
                    Long.class, committed)).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
