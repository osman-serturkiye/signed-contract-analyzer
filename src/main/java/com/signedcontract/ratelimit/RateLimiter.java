package com.signedcontract.ratelimit;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Bounds the number of concurrently in-flight tasks (e.g. AI/OCR HTTP calls)
 * using a {@link Semaphore}. Tasks beyond {@code maxConcurrency} are queued
 * on the underlying executor and only start once a permit is released.
 *
 * <p>Validates: Requirements 23.1, 23.2</p>
 */
public class RateLimiter {

    private final Semaphore semaphore;
    private final ExecutorService executor;
    private final int maxConcurrency;
    private volatile boolean shutdown = false;

    public RateLimiter(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1, was: " + maxConcurrency);
        }
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(maxConcurrency, true);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "rate-limiter-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submits {@code task} for execution. The task does not actually start
     * running until a permit is available, guaranteeing that no more than
     * {@code maxConcurrency} tasks run at the same instant.
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        if (shutdown) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("RateLimiter has been shut down"));
            return failed;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.submit(() -> {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                T result = task.call();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        });
        return future;
    }

    /** Number of permits currently in use — useful for tests/property assertions. */
    public int activeCount() {
        return maxConcurrency - semaphore.availablePermits();
    }

    public void shutdown() {
        shutdown = true;
        executor.shutdown();
    }
}
