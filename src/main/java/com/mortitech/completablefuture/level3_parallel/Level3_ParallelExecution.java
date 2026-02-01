package com.mortitech.completablefuture.level3_parallel;

import com.mortitech.completablefuture.domain.DashboardData;
import com.mortitech.completablefuture.domain.Order;
import com.mortitech.completablefuture.domain.Payment;
import com.mortitech.completablefuture.domain.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 3 — PARALLEL EXECUTION: Running Independent Operations Concurrently
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHY PARALLEL EXECUTION:
 * When you have multiple INDEPENDENT I/O operations, running them in parallel
 * can dramatically reduce total latency.
 *
 * Example: Dashboard loading
 * - Sequential: user(100ms) + orders(150ms) + payments(120ms) = 370ms
 * - Parallel:   max(100ms, 150ms, 120ms) = 150ms  ← 2.5x faster!
 *
 * KEY CONCEPTS:
 * 1. allOf()     - Wait for ALL futures to complete
 * 2. anyOf()     - Wait for ANY ONE future to complete (first wins)
 * 3. thenCombine - Combine exactly TWO futures
 * 4. Custom Executor - Control thread pool for better resource management
 *
 * THREAD POOL SIZING GUIDELINES:
 * - I/O-bound tasks: threads = CPU cores × (1 + wait_time / compute_time)
 *   Typically 2-4× CPU cores for I/O heavy work
 * - CPU-bound tasks: threads = CPU cores (or cores + 1)
 * - Mixed workloads: separate pools for I/O and CPU tasks
 */
public class Level3_ParallelExecution {

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOM EXECUTOR CONFIGURATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Custom executor for I/O-bound operations.
     *
     * WHY custom executor instead of ForkJoinPool.commonPool()?
     * 1. commonPool is shared across the JVM - one misbehaving library can starve your app
     * 2. commonPool size = CPU cores - 1, often too small for I/O-bound work
     * 3. Custom pool gives you control over thread naming (crucial for debugging)
     * 4. You can tune pool size based on your specific workload
     */
    private final ExecutorService ioExecutor;

    public Level3_ParallelExecution() {
        int poolSize = calculateOptimalPoolSize();
        this.ioExecutor = Executors.newFixedThreadPool(poolSize, new NamedThreadFactory("io-pool"));
    }

    /**
     * Calculate optimal thread pool size for I/O-bound operations.
     *
     * Formula: N_threads = N_cpu × (1 + W/C)
     * Where:
     *   N_cpu = number of CPU cores
     *   W = wait time (time spent waiting for I/O)
     *   C = compute time (time spent doing actual computation)
     *
     * For I/O-heavy work, W/C is typically high (10-100), so we use more threads.
     * Rule of thumb: 2-4× CPU cores for I/O-bound, equal to cores for CPU-bound.
     */
    private int calculateOptimalPoolSize() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        // For I/O-bound work with ~90% wait time, multiply by ~2-4
        // Being conservative here; in production, tune based on metrics
        return cpuCores * 2;
    }

    /**
     * Custom ThreadFactory for meaningful thread names.
     * This is CRITICAL for debugging - you'll thank yourself in production.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String prefix;

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true); // Don't prevent JVM shutdown
            return thread;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIMULATED ASYNC SERVICES
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<User> fetchUser(Long userId, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(100);
            return User.of(userId, "alice", "alice@example.com", "PREMIUM");
        }, executor);
    }

    public CompletableFuture<List<Order>> fetchOrders(Long userId, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(150);
            return List.of(
                Order.of(1L, userId, List.of("Laptop", "Mouse"), new BigDecimal("1299.99"), "DELIVERED", LocalDateTime.now().minusDays(5)),
                Order.of(2L, userId, List.of("Keyboard"), new BigDecimal("149.99"), "SHIPPED", LocalDateTime.now().minusDays(1))
            );
        }, executor);
    }

    public CompletableFuture<List<Payment>> fetchPayments(Long userId, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(120);
            return List.of(
                Payment.of(1L, 1L, new BigDecimal("1299.99"), "COMPLETED", "CARD", LocalDateTime.now().minusDays(5)),
                Payment.of(2L, 2L, new BigDecimal("149.99"), "PENDING", "PAYPAL", LocalDateTime.now().minusDays(1))
            );
        }, executor);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 1: allOf - Run All Operations in Parallel
     * ─────────────────────────────────────────────────────────────────────────
     *
     * allOf() returns a CompletableFuture<Void> that completes when ALL
     * input futures complete. It doesn't give you the results directly -
     * you need to extract them from the original futures.
     */
    public CompletableFuture<DashboardData> loadDashboardWithAllOf(Long userId) {
        // Launch all three operations in PARALLEL
        CompletableFuture<User> userFuture = fetchUser(userId, ioExecutor);
        CompletableFuture<List<Order>> ordersFuture = fetchOrders(userId, ioExecutor);
        CompletableFuture<List<Payment>> paymentsFuture = fetchPayments(userId, ioExecutor);

        // allOf waits for ALL to complete
        return CompletableFuture.allOf(userFuture, ordersFuture, paymentsFuture)
            .thenApply(ignored -> {
                // At this point, all futures are complete - join() won't block
                User user = userFuture.join();
                List<Order> orders = ordersFuture.join();
                List<Payment> payments = paymentsFuture.join();

                return DashboardData.of(
                    user,
                    orders,
                    payments,
                    orders.size(),
                    calculateAccountHealth(payments)
                );
            });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 2: thenCombine - Combine Exactly Two Futures
     * ─────────────────────────────────────────────────────────────────────────
     *
     * thenCombine is cleaner when combining exactly two futures.
     * For more than two, use allOf or chain multiple thenCombine calls.
     */
    public CompletableFuture<String> getUserOrderSummary(Long userId) {
        CompletableFuture<User> userFuture = fetchUser(userId, ioExecutor);
        CompletableFuture<List<Order>> ordersFuture = fetchOrders(userId, ioExecutor);

        // thenCombine runs both in parallel and combines when both complete
        return userFuture.thenCombine(ordersFuture, (user, orders) ->
            String.format("%s has %d orders", user.username(), orders.size())
        );
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 3: Chaining thenCombine for Three+ Futures
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Alternative to allOf when you want type-safe combination.
     */
    public CompletableFuture<DashboardData> loadDashboardWithCombine(Long userId) {
        CompletableFuture<User> userFuture = fetchUser(userId, ioExecutor);
        CompletableFuture<List<Order>> ordersFuture = fetchOrders(userId, ioExecutor);
        CompletableFuture<List<Payment>> paymentsFuture = fetchPayments(userId, ioExecutor);

        // Chain thenCombine calls - a bit verbose but type-safe
        return userFuture
            .thenCombine(ordersFuture, (user, orders) ->
                new UserWithOrders(user, orders))
            .thenCombine(paymentsFuture, (userWithOrders, payments) ->
                DashboardData.of(
                    userWithOrders.user(),
                    userWithOrders.orders(),
                    payments,
                    userWithOrders.orders().size(),
                    calculateAccountHealth(payments)
                )
            );
    }

    // Intermediate record for type-safe combining
    private record UserWithOrders(User user, List<Order> orders) {}

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 4: anyOf - First Result Wins (Racing Pattern)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * anyOf() completes as soon as ANY input future completes.
     * Useful for:
     * - Redundant requests to multiple services (first response wins)
     * - Timeout implementation (race with a delayed timeout future)
     * - Cache-aside pattern (race cache lookup vs database query)
     */
    public CompletableFuture<User> fetchUserFromFastestSource(Long userId) {
        // Race between primary and backup service
        CompletableFuture<User> primaryService = fetchUser(userId, ioExecutor);
        CompletableFuture<User> backupService = fetchUserFromBackup(userId);

        // Returns as soon as EITHER completes
        return CompletableFuture.anyOf(primaryService, backupService)
            .thenApply(result -> (User) result);  // anyOf returns Object, need to cast
    }

    private CompletableFuture<User> fetchUserFromBackup(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(80); // Backup might be faster sometimes
            return User.of(userId, "alice_backup", "alice@backup.com", "PREMIUM");
        }, ioExecutor);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 5: Using Virtual Threads (Java 21+)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Java 21's virtual threads are perfect for I/O-bound async operations.
     * They're lightweight and can scale to millions of concurrent tasks.
     *
     * Key benefits:
     * - No thread pool sizing headaches
     * - Near-zero memory overhead per thread
     * - Perfect for high-concurrency I/O workloads
     */
    public CompletableFuture<DashboardData> loadDashboardWithVirtualThreads(Long userId) {
        // Virtual thread executor - creates new virtual thread per task
        Executor virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        CompletableFuture<User> userFuture = fetchUser(userId, virtualExecutor);
        CompletableFuture<List<Order>> ordersFuture = fetchOrders(userId, virtualExecutor);
        CompletableFuture<List<Payment>> paymentsFuture = fetchPayments(userId, virtualExecutor);

        return CompletableFuture.allOf(userFuture, ordersFuture, paymentsFuture)
            .thenApply(ignored -> DashboardData.of(
                userFuture.join(),
                ordersFuture.join(),
                paymentsFuture.join(),
                ordersFuture.join().size(),
                calculateAccountHealth(paymentsFuture.join())
            ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMPARISON: Sequential vs Parallel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ❌ ANTI-PATTERN: Sequential execution wastes time
     */
    public CompletableFuture<DashboardData> loadDashboardSequential_SLOW(Long userId) {
        return fetchUser(userId, ioExecutor)
            .thenCompose(user ->
                fetchOrders(userId, ioExecutor)
                    .thenCompose(orders ->
                        fetchPayments(userId, ioExecutor)
                            .thenApply(payments -> DashboardData.of(
                                user, orders, payments,
                                orders.size(),
                                calculateAccountHealth(payments)
                            ))
                    )
            );
        // Total time: 100 + 150 + 120 = 370ms
    }

    /**
     * ✅ CORRECT: Parallel execution is much faster
     */
    public CompletableFuture<DashboardData> loadDashboardParallel_FAST(Long userId) {
        return loadDashboardWithAllOf(userId);
        // Total time: max(100, 150, 120) = 150ms
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────

    private String calculateAccountHealth(List<Payment> payments) {
        long failedPayments = payments.stream()
            .filter(p -> "FAILED".equals(p.status()))
            .count();

        if (failedPayments > 2) return "CRITICAL";
        if (failedPayments > 0) return "WARNING";
        return "GOOD";
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the executor for external use (e.g., in Spring config).
     */
    public Executor getIoExecutor() {
        return ioExecutor;
    }

    /**
     * Cleanup - call this on application shutdown.
     */
    public void shutdown() {
        ioExecutor.shutdown();
    }
}
