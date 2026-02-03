package com.mortitech.completablefuture.level6_advanced;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * HIGH PRESSURE PATTERNS: Handling Fire-and-Forget Under Load
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PROBLEM: When many fire-and-forget tasks arrive faster than they can be processed:
 * 1. Thread pool exhaustion - all threads busy, queue fills up
 * 2. Memory exhaustion - unbounded queues grow indefinitely
 * 3. System becomes unresponsive
 * 4. Tasks get rejected or lost
 *
 * SOLUTIONS COVERED:
 * 1. Bounded queues with rejection policies
 * 2. Backpressure mechanisms
 * 3. Batching for database writes
 * 4. Circuit breaker pattern
 * 5. Graceful degradation
 *
 * REAL-WORLD SCENARIO: Event logging to database
 * - High volume: 10,000+ events/second
 * - Database can handle ~1,000 writes/second
 * - Without proper handling: system crashes or loses events
 */
public class HighPressurePatterns {

    // ═══════════════════════════════════════════════════════════════════════════════
    // METRICS AND MONITORING
    // ═══════════════════════════════════════════════════════════════════════════════

    private final AtomicInteger tasksSubmitted = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);
    private final AtomicInteger tasksRejected = new AtomicInteger(0);
    private final AtomicInteger tasksFailed = new AtomicInteger(0);
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 1: THREAD POOL EXHAUSTION DEMONSTRATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * ❌ DANGEROUS: Unbounded queue leads to memory exhaustion
     *
     * Default Executors.newFixedThreadPool uses LinkedBlockingQueue with no limit.
     * Under high load, the queue grows until OutOfMemoryError.
     */
    public ExecutorService createDangerousUnboundedPool(int threads) {
        // LinkedBlockingQueue with no capacity = UNBOUNDED = DANGEROUS
        return new ThreadPoolExecutor(
            threads, threads,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()  // NO LIMIT - will grow until OOM!
        );
    }

    /**
     * ✅ SAFE: Bounded queue with rejection policy
     *
     * When queue is full, rejection policy determines what happens.
     */
    public ExecutorService createBoundedPoolWithPolicy(
            int threads,
            int queueCapacity,
            RejectedExecutionHandler rejectionPolicy) {

        return new ThreadPoolExecutor(
            threads, threads,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),  // BOUNDED queue
            rejectionPolicy
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 2: REJECTION POLICIES EXPLAINED
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Policy 1: AbortPolicy (default) - Throws RejectedExecutionException
     * Use when: You want to know immediately when system is overloaded
     */
    public ExecutorService createPoolWithAbortPolicy(int threads, int queueCapacity) {
        return createBoundedPoolWithPolicy(threads, queueCapacity,
            new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Policy 2: CallerRunsPolicy - Caller thread executes the task
     * Use when: Backpressure is acceptable, slowing down producers is OK
     * BEST FOR: Fire-and-forget logging where losing events is worse than slowdown
     */
    public ExecutorService createPoolWithCallerRunsPolicy(int threads, int queueCapacity) {
        return createBoundedPoolWithPolicy(threads, queueCapacity,
            new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Policy 3: DiscardPolicy - Silently drops the task
     * Use when: Losing some tasks is acceptable (sampling, non-critical metrics)
     * DANGER: You won't know tasks are being dropped!
     */
    public ExecutorService createPoolWithDiscardPolicy(int threads, int queueCapacity) {
        return createBoundedPoolWithPolicy(threads, queueCapacity,
            new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Policy 4: DiscardOldestPolicy - Drops oldest queued task, retries new one
     * Use when: Newer data is more important than older data
     */
    public ExecutorService createPoolWithDiscardOldestPolicy(int threads, int queueCapacity) {
        return createBoundedPoolWithPolicy(threads, queueCapacity,
            new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * Policy 5: Custom policy with metrics (RECOMMENDED)
     * Tracks rejections for monitoring and alerting
     */
    public ExecutorService createPoolWithMonitoredRejection(int threads, int queueCapacity) {
        return createBoundedPoolWithPolicy(threads, queueCapacity,
            (runnable, executor) -> {
                tasksRejected.incrementAndGet();
                // In production: log.warn("Task rejected, queue full")
                // In production: metrics.increment("executor.rejected")
                System.err.println("[REJECTED] Task rejected - queue full. " +
                    "Total rejected: " + tasksRejected.get());

                // Option: run on caller thread as fallback (backpressure)
                if (!executor.isShutdown()) {
                    runnable.run();
                }
            });
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 3: DATABASE LOGGING UNDER PRESSURE
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Event to be logged to database
     */
    public record LogEvent(
        String eventType,
        String userId,
        String details,
        LocalDateTime timestamp
    ) {}

    /**
     * ❌ ANTI-PATTERN: One database write per event
     *
     * Under high load (10,000 events/sec), this creates:
     * - 10,000 database connections/second
     * - Database connection pool exhaustion
     * - Massive overhead per write
     */
    public void logEventNaive_BAD(LogEvent event, ExecutorService executor) {
        CompletableFuture.runAsync(() -> {
            // Each event = 1 database write = SLOW under load
            simulateDatabaseWrite(List.of(event));
        }, executor);
    }

    /**
     * ✅ PATTERN: Batched database writes
     *
     * Collects events and writes them in batches.
     * Much more efficient: 100 events in 1 write vs 100 separate writes.
     */
    public class BatchingEventLogger {
        private final BlockingQueue<LogEvent> eventBuffer;
        private final int batchSize;
        private final long maxDelayMs;
        private final ExecutorService executor;
        private final ScheduledExecutorService scheduler;
        private final Consumer<List<LogEvent>> batchWriter;
        private volatile boolean running = true;

        public BatchingEventLogger(
                int bufferCapacity,
                int batchSize,
                long maxDelayMs,
                ExecutorService executor,
                Consumer<List<LogEvent>> batchWriter) {

            this.eventBuffer = new ArrayBlockingQueue<>(bufferCapacity);
            this.batchSize = batchSize;
            this.maxDelayMs = maxDelayMs;
            this.executor = executor;
            this.batchWriter = batchWriter;
            this.scheduler = new ScheduledThreadPoolExecutor(1);

            // Start periodic flush
            startPeriodicFlush();
        }

        /**
         * Non-blocking event submission.
         * Returns false if buffer is full (backpressure signal).
         */
        public boolean logEvent(LogEvent event) {
            tasksSubmitted.incrementAndGet();
            boolean added = eventBuffer.offer(event);

            if (!added) {
                tasksRejected.incrementAndGet();
                System.err.println("[BUFFER FULL] Event dropped: " + event.eventType());
                return false;
            }

            // If batch size reached, trigger immediate flush
            if (eventBuffer.size() >= batchSize) {
                triggerFlush();
            }

            return true;
        }

        /**
         * Blocking event submission with timeout.
         * Waits for space if buffer is full.
         */
        public boolean logEventBlocking(LogEvent event, long timeoutMs) throws InterruptedException {
            tasksSubmitted.incrementAndGet();
            boolean added = eventBuffer.offer(event, timeoutMs, TimeUnit.MILLISECONDS);

            if (!added) {
                tasksRejected.incrementAndGet();
                return false;
            }

            if (eventBuffer.size() >= batchSize) {
                triggerFlush();
            }

            return true;
        }

        private void startPeriodicFlush() {
            scheduler.scheduleAtFixedRate(
                this::triggerFlush,
                maxDelayMs, maxDelayMs, TimeUnit.MILLISECONDS
            );
        }

        private void triggerFlush() {
            if (!running && eventBuffer.isEmpty()) return;
            if (eventBuffer.isEmpty()) return;

            CompletableFuture.runAsync(() -> {
                List<LogEvent> batch = new ArrayList<>(batchSize);
                eventBuffer.drainTo(batch, batchSize);

                if (!batch.isEmpty()) {
                    long start = System.currentTimeMillis();
                    try {
                        batchWriter.accept(batch);
                        tasksCompleted.addAndGet(batch.size());
                        totalProcessingTimeMs.addAndGet(System.currentTimeMillis() - start);
                    } catch (Exception e) {
                        tasksFailed.addAndGet(batch.size());
                        System.err.println("[BATCH WRITE FAILED] " + batch.size() +
                            " events lost: " + e.getMessage());
                    }
                }
            }, executor);
        }

        public void shutdown() {
            running = false;
            scheduler.shutdown();
            // Final flush
            triggerFlush();
        }

        public int getBufferSize() {
            return eventBuffer.size();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 4: SEMAPHORE-BASED RATE LIMITING
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Rate-limited fire-and-forget with semaphore.
     * Limits concurrent operations regardless of queue size.
     */
    public class RateLimitedLogger {
        private final Semaphore permits;
        private final ExecutorService executor;
        private final Consumer<LogEvent> eventWriter;

        public RateLimitedLogger(int maxConcurrent, ExecutorService executor,
                                  Consumer<LogEvent> eventWriter) {
            this.permits = new Semaphore(maxConcurrent);
            this.executor = executor;
            this.eventWriter = eventWriter;
        }

        /**
         * Non-blocking attempt to log.
         * Returns false immediately if rate limit reached.
         */
        public boolean tryLogEvent(LogEvent event) {
            if (!permits.tryAcquire()) {
                tasksRejected.incrementAndGet();
                return false; // Rate limit reached
            }

            tasksSubmitted.incrementAndGet();
            CompletableFuture.runAsync(() -> {
                try {
                    eventWriter.accept(event);
                    tasksCompleted.incrementAndGet();
                } catch (Exception e) {
                    tasksFailed.incrementAndGet();
                } finally {
                    permits.release();
                }
            }, executor);

            return true;
        }

        /**
         * Blocking log with timeout.
         * Waits for permit if rate limit reached.
         */
        public boolean logEventWithWait(LogEvent event, long timeoutMs) throws InterruptedException {
            if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                tasksRejected.incrementAndGet();
                return false;
            }

            tasksSubmitted.incrementAndGet();
            CompletableFuture.runAsync(() -> {
                try {
                    eventWriter.accept(event);
                    tasksCompleted.incrementAndGet();
                } catch (Exception e) {
                    tasksFailed.incrementAndGet();
                } finally {
                    permits.release();
                }
            }, executor);

            return true;
        }

        public int getAvailablePermits() {
            return permits.availablePermits();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 5: CIRCUIT BREAKER FOR DATABASE WRITES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Simple circuit breaker to prevent hammering a failing database.
     */
    public class CircuitBreakerLogger {
        private final ExecutorService executor;
        private final Consumer<LogEvent> eventWriter;

        // Circuit breaker state
        private volatile boolean circuitOpen = false;
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final int failureThreshold;
        private volatile long circuitOpenedAt = 0;
        private final long resetTimeoutMs;

        public CircuitBreakerLogger(
                ExecutorService executor,
                Consumer<LogEvent> eventWriter,
                int failureThreshold,
                long resetTimeoutMs) {
            this.executor = executor;
            this.eventWriter = eventWriter;
            this.failureThreshold = failureThreshold;
            this.resetTimeoutMs = resetTimeoutMs;
        }

        public boolean logEvent(LogEvent event) {
            // Check if circuit is open
            if (circuitOpen) {
                if (System.currentTimeMillis() - circuitOpenedAt > resetTimeoutMs) {
                    // Try to close circuit (half-open state)
                    circuitOpen = false;
                    consecutiveFailures.set(0);
                    System.out.println("[CIRCUIT BREAKER] Attempting to close circuit...");
                } else {
                    tasksRejected.incrementAndGet();
                    System.err.println("[CIRCUIT BREAKER] Circuit OPEN - rejecting event");
                    return false;
                }
            }

            tasksSubmitted.incrementAndGet();
            CompletableFuture.runAsync(() -> {
                try {
                    eventWriter.accept(event);
                    tasksCompleted.incrementAndGet();
                    consecutiveFailures.set(0); // Reset on success
                } catch (Exception e) {
                    tasksFailed.incrementAndGet();
                    int failures = consecutiveFailures.incrementAndGet();

                    if (failures >= failureThreshold) {
                        circuitOpen = true;
                        circuitOpenedAt = System.currentTimeMillis();
                        System.err.println("[CIRCUIT BREAKER] Circuit OPENED after " +
                            failures + " consecutive failures");
                    }
                }
            }, executor);

            return true;
        }

        public boolean isCircuitOpen() {
            return circuitOpen;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SIMULATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Simulates database write with configurable latency and failure rate.
     */
    public void simulateDatabaseWrite(List<LogEvent> events) {
        simulateDatabaseWrite(events, 10, 0.0); // 10ms latency, no failures
    }

    public void simulateDatabaseWrite(List<LogEvent> events, long latencyMs, double failureRate) {
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (Math.random() < failureRate) {
            throw new RuntimeException("Simulated database failure");
        }

        // In real code: jdbcTemplate.batchUpdate(...)
        System.out.println("[DB] Wrote " + events.size() + " events to database");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // METRICS
    // ═══════════════════════════════════════════════════════════════════════════════

    public int getTasksSubmitted() { return tasksSubmitted.get(); }
    public int getTasksCompleted() { return tasksCompleted.get(); }
    public int getTasksRejected() { return tasksRejected.get(); }
    public int getTasksFailed() { return tasksFailed.get(); }
    public long getTotalProcessingTimeMs() { return totalProcessingTimeMs.get(); }

    public void resetMetrics() {
        tasksSubmitted.set(0);
        tasksCompleted.set(0);
        tasksRejected.set(0);
        tasksFailed.set(0);
        totalProcessingTimeMs.set(0);
    }

    public String getMetricsSummary() {
        return String.format(
            "Submitted: %d, Completed: %d, Rejected: %d, Failed: %d",
            tasksSubmitted.get(), tasksCompleted.get(),
            tasksRejected.get(), tasksFailed.get()
        );
    }
}
