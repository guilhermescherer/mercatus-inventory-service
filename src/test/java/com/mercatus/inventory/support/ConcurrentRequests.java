package com.mercatus.inventory.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ConcurrentRequests {

    private ConcurrentRequests() {
    }

    public static <T> List<T> run(List<Callable<T>> calls) throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(calls.size());
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }
                    return call.call();
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready");
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
        }
    }
}
