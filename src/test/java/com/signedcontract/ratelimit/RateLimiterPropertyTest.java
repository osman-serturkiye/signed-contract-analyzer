package com.signedcontract.ratelimit;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link RateLimiter}.
 *
 * <p>Property 12: RateLimiter Eşzamanlılık Üst Sınırı</p>
 * <p>Validates: Requirements 23.1, 23.2</p>
 */
class RateLimiterPropertyTest {

    @Property(tries = 20)
    void neverExceedsMaxConcurrency(
            @ForAll @IntRange(min = 1, max = 8) int maxConcurrency,
            @ForAll @IntRange(min = 1, max = 30) int taskCount) throws Exception {

        RateLimiter limiter = new RateLimiter(maxConcurrency);
        AtomicInteger current = new AtomicInteger(0);
        AtomicInteger observedMax = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            CompletableFuture<Void> f = limiter.submit(() -> {
                int now = current.incrementAndGet();
                observedMax.updateAndGet(prev -> Math.max(prev, now));
                Thread.sleep(5);
                current.decrementAndGet();
                return null;
            });
            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        limiter.shutdown();

        assertThat(observedMax.get())
            .as("Observed concurrent task count must never exceed maxConcurrency=%d", maxConcurrency)
            .isLessThanOrEqualTo(maxConcurrency);
    }
}
