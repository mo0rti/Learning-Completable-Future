package com.mortitech.completablefuture.level6_advanced;

import com.mortitech.completablefuture.domain.User;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 6 — ADVANCED PATTERNS: Production-Ready Async Techniques
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This level covers advanced patterns you'll encounter in production:
 * 1. Timeouts and cancellation
 * 2. Rate limiting and backpressure
 * 3. Retry patterns
 * 4. Circuit breaker basics
 * 5. Async orchestration
 * 6. Thread starvation prevention
 */
public class Level6_AdvancedPatterns {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /**
     * ═══════════════════════════════════════════════════════════════════════════════
     * SECTION 1: TIMEOUTS AND CANCELLATION
     * ═══════════════════════════════════════════════════════════════════════════════
     */

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 1: orTimeout (Java 9+) - Fail Fast
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Completes exceptionally with TimeoutException if not done in time.
     * Use when you want to fail and propagate the error.
     */
    public CompletableFuture<User> fetchUserWithTimeout(Long userId, Duration timeout) {
        return fetchUserSlow(userId)
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        // Throws TimeoutException if not completed within timeout
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 2: completeOnTimeout (Java 9+) - Graceful Degradation
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Returns a default value if not completed in time.
     * Use when you can provide a sensible fallback.
     */
    public CompletableFuture<User> fetchUserWithFallbackTimeout(Long userId, Duration timeout) {
        return fetchUserSlow(userId)
            .completeOnTimeout(
                User.empty(),  // Fallback value
                timeout.toMillis(),
                TimeUnit.MILLISECONDS
            );
        // Returns empty user instead of failing
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 3: Manual Timeout with Racing (Pre-Java 9 compatible)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Race the actual operation against a delayed timeout future.
     */
    public CompletableFuture<User> fetchUserWithManualTimeout(Long userId, Duration timeout) {
        CompletableFuture<User> dataFuture = fetchUserSlow(userId);
        CompletableFuture<User> timeoutFuture = createTimeoutFuture(timeout);

        return CompletableFuture.anyOf(dataFuture, timeoutFuture)
            .thenApply(result -> (User) result);
    }

    private CompletableFuture<User> createTimeoutFuture(Duration timeout) {
        CompletableFuture<User> future = new CompletableFuture<>();
        scheduler.schedule(
            () -> future.completeExceptionally(
                new TimeoutException("Operation timed out after " + timeout)
            ),
            timeout.toMillis(),
            TimeUnit.MILLISECONDS
        );
        return future;
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 4: Cancellation
     * ─────────────────────────────────────────────────────────────────────────
     *
     * cancel() marks the future as cancelled but doesn't interrupt the task.
     * The underlying task continues running - only the future is affected.
     */
    public CompletableFuture<User> fetchUserCancellable(Long userId) {
        CompletableFuture<User> future = fetchUserSlow(userId);

        // Schedule cancellation after 100ms
        scheduler.schedule(
            () -> {
                boolean cancelled = future.cancel(true);
                if (cancelled) {
                    System.out.println("Future was cancelled");
                }
            },
            100,
            TimeUnit.MILLISECONDS
        );

        return future;
        // Note: The underlying task still runs to completion!
        // CompletableFuture cancellation only affects the future, not the task.
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════
     * SECTION 2: RETRY PATTERNS
     * ═══════════════════════════════════════════════════════════════════════════════
     */

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 5: Simple Retry with Limit
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Retry failed operations up to N times.
     */
    public CompletableFuture<User> fetchUserWithRetry(Long userId, int maxRetries) {
        return retryAsync(
            () -> fetchUserThatMayFail(userId),
            maxRetries
        );
    }

    private <T> CompletableFuture<T> retryAsync(Supplier<CompletableFuture<T>> operation, int maxRetries) {
        return operation.get()
            .thenApply(CompletableFuture::completedFuture)
            .exceptionally(throwable -> {
                if (maxRetries > 0) {
                    System.out.println("Retrying... attempts left: " + maxRetries);
                    return retryAsync(operation, maxRetries - 1);
                }
                return CompletableFuture.failedFuture(throwable);
            })
            .thenCompose(f -> f);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 6: Retry with Exponential Backoff
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Wait progressively longer between retries.
     * Prevents thundering herd on service recovery.
     */
    public CompletableFuture<User> fetchUserWithExponentialBackoff(Long userId) {
        return retryWithBackoff(
            () -> fetchUserThatMayFail(userId),
            3,                    // max retries
            Duration.ofMillis(100) // initial delay
        );
    }

    private <T> CompletableFuture<T> retryWithBackoff(
            Supplier<CompletableFuture<T>> operation,
            int retriesLeft,
            Duration delay) {

        return operation.get()
            .exceptionallyCompose(throwable -> {
                if (retriesLeft <= 0) {
                    return CompletableFuture.failedFuture(throwable);
                }

                System.out.printf("Retry in %dms, attempts left: %d%n",
                    delay.toMillis(), retriesLeft);

                // Schedule retry after delay
                CompletableFuture<T> delayed = new CompletableFuture<>();
                scheduler.schedule(
                    () -> retryWithBackoff(
                        operation,
                        retriesLeft - 1,
                        delay.multipliedBy(2)  // Exponential increase
                    ).whenComplete((result, ex) -> {
                        if (ex != null) delayed.completeExceptionally(ex);
                        else delayed.complete(result);
                    }),
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS
                );
                return delayed;
            });
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════
     * SECTION 3: RATE LIMITING AND BACKPRESSURE
     * ═══════════════════════════════════════════════════════════════════════════════
     */

    // Semaphore limits concurrent operations
    private final Semaphore rateLimiter = new Semaphore(10); // Max 10 concurrent

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 7: Semaphore-based Rate Limiting
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Limit concurrent operations to prevent overwhelming downstream services.
     */
    public CompletableFuture<User> fetchUserRateLimited(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Acquire permit (blocks if limit reached)
                rateLimiter.acquire();
                try {
                    return fetchUserSlow(userId).join();
                } finally {
                    rateLimiter.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for permit", e);
            }
        }, executor);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 8: Batch Processing with Limited Concurrency
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Process a list of items with controlled parallelism.
     */
    public CompletableFuture<List<User>> fetchUsersWithConcurrencyLimit(
            List<Long> userIds, int maxConcurrent) {

        Semaphore semaphore = new Semaphore(maxConcurrent);

        List<CompletableFuture<User>> futures = userIds.stream()
            .map(userId -> CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return fetchUserSlow(userId).join();
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }, executor))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════
     * SECTION 4: ASYNC ORCHESTRATION PATTERNS
     * ═══════════════════════════════════════════════════════════════════════════════
     */

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 9: First Successful (Hedged Requests)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Send same request to multiple services, use first successful response.
     * Useful for reducing latency variance (tail latency).
     */
    public CompletableFuture<User> fetchUserHedged(Long userId) {
        CompletableFuture<User> primary = fetchUserFromService("primary", userId);
        CompletableFuture<User> secondary = fetchUserFromService("secondary", userId);

        // anyOf returns first completed (success OR failure)
        // We want first SUCCESSFUL, so we need custom logic
        return firstSuccessful(List.of(primary, secondary));
    }

    private <T> CompletableFuture<T> firstSuccessful(List<CompletableFuture<T>> futures) {
        CompletableFuture<T> result = new CompletableFuture<>();
        var remaining = new java.util.concurrent.atomic.AtomicInteger(futures.size());

        for (CompletableFuture<T> future : futures) {
            future.whenComplete((value, error) -> {
                if (error == null) {
                    // First success wins
                    result.complete(value);
                } else if (remaining.decrementAndGet() == 0) {
                    // All failed
                    result.completeExceptionally(
                        new RuntimeException("All sources failed"));
                }
            });
        }

        return result;
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 10: Dependent Fan-Out
     * ─────────────────────────────────────────────────────────────────────────
     *
     * First operation produces a list, then fan out to process each item.
     */
    public CompletableFuture<List<User>> fetchUserAndFriends(Long userId) {
        return fetchUser(userId)
            .thenCompose(user -> {
                // Get friend IDs (simulated)
                List<Long> friendIds = List.of(userId + 1, userId + 2, userId + 3);

                // Fan out to fetch all friends in parallel
                List<CompletableFuture<User>> friendFutures = friendIds.stream()
                    .map(this::fetchUser)
                    .collect(Collectors.toList());

                return CompletableFuture.allOf(friendFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> friendFutures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
            });
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════
     * SECTION 5: AVOIDING THREAD STARVATION
     * ═══════════════════════════════════════════════════════════════════════════════
     */

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ANTI-PATTERN: Nested Blocking Calls Cause Deadlock
     * ─────────────────────────────────────────────────────────────────────────
     *
     * When using a fixed thread pool and blocking with join() inside async tasks,
     * you can exhaust all threads waiting for inner tasks that can never execute.
     */
    public CompletableFuture<String> deadlockExample_DANGEROUS() {
        // Using small pool to demonstrate the issue
        ExecutorService smallPool = Executors.newFixedThreadPool(2);

        // This can deadlock!
        return CompletableFuture.supplyAsync(() -> {
            // Outer task running on thread 1

            // Inner task needs a thread, but if pool is exhausted...
            CompletableFuture<String> inner = CompletableFuture.supplyAsync(
                () -> "inner result",
                smallPool
            );

            // ... this join() blocks thread 1, waiting for inner
            // If all threads are blocked like this, deadlock!
            return "outer: " + inner.join();
        }, smallPool);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * CORRECT: Use thenCompose to Avoid Blocking
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Never call join()/get() inside async tasks. Chain with thenCompose instead.
     */
    public CompletableFuture<String> noDeadlockExample_SAFE() {
        return CompletableFuture.supplyAsync(() -> "outer", executor)
            .thenComposeAsync(outer ->
                CompletableFuture.supplyAsync(() -> "inner", executor)
                    .thenApply(inner -> outer + ": " + inner),
                executor
            );
        // No blocking, no deadlock risk
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 11: Virtual Threads Eliminate Starvation (Java 21+)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Virtual threads don't have the same starvation issues because blocking
     * a virtual thread doesn't block an OS thread.
     */
    public CompletableFuture<String> virtualThreadExample_SAFE() {
        return CompletableFuture.supplyAsync(() -> {
            // Safe to block with virtual threads - they're cheap
            CompletableFuture<String> inner = CompletableFuture.supplyAsync(
                () -> "inner result",
                Executors.newVirtualThreadPerTaskExecutor()
            );
            return "outer: " + inner.join();
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────

    private CompletableFuture<User> fetchUser(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            return User.of(userId, "user_" + userId, "user" + userId + "@example.com", "BASIC");
        }, executor);
    }

    private CompletableFuture<User> fetchUserSlow(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(500); // Slow!
            return User.of(userId, "slow_user", "slow@example.com", "BASIC");
        }, executor);
    }

    private int failureCount = 0;
    private CompletableFuture<User> fetchUserThatMayFail(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            // Fail first 2 times, succeed on 3rd
            if (++failureCount < 3) {
                throw new RuntimeException("Simulated failure #" + failureCount);
            }
            failureCount = 0; // Reset for next test
            return User.of(userId, "recovered_user", "recovered@example.com", "BASIC");
        }, executor);
    }

    private CompletableFuture<User> fetchUserFromService(String service, Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate variable latency
            simulateLatency(service.equals("primary") ? 100 : 80);
            return User.of(userId, service + "_user", service + "@example.com", "BASIC");
        }, executor);
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        executor.shutdown();
        scheduler.shutdown();
    }
}
