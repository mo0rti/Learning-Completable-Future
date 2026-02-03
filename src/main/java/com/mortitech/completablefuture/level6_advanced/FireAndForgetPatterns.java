package com.mortitech.completablefuture.level6_advanced;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * FIRE-AND-FORGET PATTERNS: When You Don't Return CompletableFuture
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Fire-and-forget is when you start an async operation but don't wait for
 * or track its completion. The caller continues immediately.
 *
 * WHEN TO USE:
 * ✅ Non-critical operations (logging, analytics, metrics)
 * ✅ Notifications where failure is acceptable
 * ✅ Cache warming / pre-loading
 * ✅ Audit trails that shouldn't block main flow
 *
 * WHEN NOT TO USE:
 * ❌ Critical business operations
 * ❌ Operations where failure must be reported
 * ❌ When you need to track completion
 * ❌ When backpressure/rate limiting is needed
 *
 * DANGERS:
 * 1. Exceptions are silently swallowed
 * 2. No way to know if/when operation completed
 * 3. Can overwhelm system (no backpressure)
 * 4. Hard to test and debug
 * 5. Resource leaks if not careful
 */
public class FireAndForgetPatterns {

    private final Executor executor;

    // For tracking in tests and monitoring
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private Consumer<Throwable> errorHandler = ex -> {}; // Default: no-op

    public FireAndForgetPatterns() {
        this.executor = Executors.newFixedThreadPool(4);
    }

    public FireAndForgetPatterns(Executor executor) {
        this.executor = executor;
    }

    /**
     * Set custom error handler for monitoring/alerting.
     */
    public void setErrorHandler(Consumer<Throwable> handler) {
        this.errorHandler = handler;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ANTI-PATTERNS: How NOT to do fire-and-forget
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * ❌ DANGEROUS: Exception is completely lost
     *
     * If the async operation fails, no one will ever know.
     * This is the worst pattern - never do this in production.
     */
    public void dangerousFireAndForget_BAD(Runnable task) {
        CompletableFuture.runAsync(task, executor);
        // Exception? What exception? It's gone forever.
    }

    /**
     * ❌ DANGEROUS: Looks safe but isn't
     *
     * The exceptionally block catches the error, but then what?
     * Just returning null doesn't actually DO anything with the error.
     */
    public void misleadingFireAndForget_BAD(Runnable task) {
        CompletableFuture.runAsync(task, executor)
            .exceptionally(ex -> {
                // This catches the exception but does nothing useful
                return null; // Error is still effectively lost
            });
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SAFE PATTERNS: Proper fire-and-forget implementations
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * ✅ SAFE: Fire-and-forget with proper error logging
     *
     * At minimum, ALWAYS log errors in fire-and-forget operations.
     * This is the baseline safe pattern.
     */
    public void safeFireAndForgetWithLogging(Runnable task, String taskName) {
        CompletableFuture.runAsync(task, executor)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    // In production, use proper logger: log.error(...)
                    System.err.println("[FIRE-AND-FORGET ERROR] Task '" + taskName +
                        "' failed: " + ex.getMessage());
                    failureCount.incrementAndGet();
                    errorHandler.accept(ex);
                } else {
                    successCount.incrementAndGet();
                }
            });
    }

    /**
     * ✅ SAFE: Fire-and-forget with metrics tracking
     *
     * Track success/failure counts for monitoring dashboards.
     * Essential for understanding system health.
     */
    public void safeFireAndForgetWithMetrics(Runnable task, String metricName) {
        long startTime = System.currentTimeMillis();

        CompletableFuture.runAsync(task, executor)
            .whenComplete((result, ex) -> {
                long duration = System.currentTimeMillis() - startTime;

                if (ex != null) {
                    // In production: metrics.increment("async." + metricName + ".failure")
                    // In production: metrics.recordTime("async." + metricName + ".duration", duration)
                    System.err.println("[METRICS] " + metricName + " FAILED in " + duration + "ms");
                    failureCount.incrementAndGet();
                    errorHandler.accept(ex);
                } else {
                    // In production: metrics.increment("async." + metricName + ".success")
                    System.out.println("[METRICS] " + metricName + " SUCCESS in " + duration + "ms");
                    successCount.incrementAndGet();
                }
            });
    }

    /**
     * ✅ SAFE: Fire-and-forget with retry on failure
     *
     * For operations that are important but shouldn't block the caller.
     * Retries silently in background.
     */
    public void safeFireAndForgetWithRetry(Runnable task, String taskName, int maxRetries) {
        executeWithRetry(task, taskName, maxRetries, 0);
    }

    private void executeWithRetry(Runnable task, String taskName, int maxRetries, int attempt) {
        CompletableFuture.runAsync(task, executor)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    if (attempt < maxRetries) {
                        System.out.println("[RETRY] Task '" + taskName +
                            "' failed, retrying... (attempt " + (attempt + 1) + "/" + maxRetries + ")");
                        // Retry after small delay
                        try {
                            Thread.sleep(100 * (attempt + 1)); // Simple backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        executeWithRetry(task, taskName, maxRetries, attempt + 1);
                    } else {
                        System.err.println("[FIRE-AND-FORGET FINAL FAILURE] Task '" + taskName +
                            "' failed after " + maxRetries + " retries: " + ex.getMessage());
                        failureCount.incrementAndGet();
                        errorHandler.accept(ex);
                    }
                } else {
                    successCount.incrementAndGet();
                }
            });
    }

    /**
     * ✅ SAFE: Fire-and-forget with fallback action
     *
     * If main action fails, execute a fallback (e.g., write to dead letter queue).
     */
    public void safeFireAndForgetWithFallback(
            Runnable primaryTask,
            Runnable fallbackTask,
            String taskName) {

        CompletableFuture.runAsync(primaryTask, executor)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    System.err.println("[FALLBACK] Primary task '" + taskName +
                        "' failed, executing fallback...");
                    try {
                        fallbackTask.run();
                        System.out.println("[FALLBACK] Fallback for '" + taskName + "' succeeded");
                    } catch (Exception fallbackEx) {
                        System.err.println("[FALLBACK FAILED] Both primary and fallback failed for '" +
                            taskName + "'");
                        failureCount.incrementAndGet();
                        errorHandler.accept(fallbackEx);
                    }
                } else {
                    successCount.incrementAndGet();
                }
            });
    }

    /**
     * ✅ SAFE: Returning future but not requiring caller to use it
     *
     * This is the BEST pattern when possible. Return the future so callers
     * CAN track it if they want, but they don't have to.
     */
    public CompletableFuture<Void> optionallyTrackedTask(Runnable task, String taskName) {
        return CompletableFuture.runAsync(task, executor)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    System.err.println("[ASYNC] Task '" + taskName + "' failed: " + ex.getMessage());
                    failureCount.incrementAndGet();
                    errorHandler.accept(ex);
                } else {
                    successCount.incrementAndGet();
                }
            });
        // Caller can:
        // 1. Ignore the return value (fire-and-forget)
        // 2. Chain with .thenRun() if they need to know when it's done
        // 3. Wait with .join() if they actually need to block
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // REAL-WORLD EXAMPLES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Example: Send analytics event (non-critical)
     */
    public void trackAnalyticsEvent(String eventName, String userId) {
        safeFireAndForgetWithLogging(
            () -> {
                // Simulate sending to analytics service
                simulateLatency(50);
                System.out.println("Analytics: " + eventName + " for user " + userId);
            },
            "analytics-" + eventName
        );
    }

    /**
     * Example: Send email notification (important but shouldn't block)
     */
    public void sendEmailNotification(String to, String subject) {
        safeFireAndForgetWithRetry(
            () -> {
                // Simulate email sending
                simulateLatency(100);
                System.out.println("Email sent to " + to + ": " + subject);
            },
            "email-notification",
            3  // Retry up to 3 times
        );
    }

    /**
     * Example: Audit log with dead letter queue fallback
     */
    public void writeAuditLog(String action, String details) {
        safeFireAndForgetWithFallback(
            () -> {
                // Primary: write to audit database
                simulateLatency(30);
                System.out.println("Audit logged: " + action);
            },
            () -> {
                // Fallback: write to local file/dead letter queue
                System.out.println("Audit written to DLQ: " + action + " - " + details);
            },
            "audit-" + action
        );
    }

    /**
     * Example: Cache warming (completely optional operation)
     */
    public void warmCache(String cacheKey) {
        safeFireAndForgetWithMetrics(
            () -> {
                // Simulate cache population
                simulateLatency(200);
                System.out.println("Cache warmed: " + cacheKey);
            },
            "cache-warm"
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MONITORING & TESTING HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public void resetCounters() {
        successCount.set(0);
        failureCount.set(0);
    }

    /**
     * Wait for all pending tasks to complete (for testing).
     * In production, you'd use executor.awaitTermination() on shutdown.
     */
    public void awaitCompletion(long maxWaitMillis) throws InterruptedException {
        Thread.sleep(maxWaitMillis);
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
