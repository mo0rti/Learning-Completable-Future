package com.mortitech.completablefuture.level6_advanced;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * FAST AUDIT OVERHEAD ANALYSIS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This test analyzes what happens when the audit operation is FAST (same speed
 * as payment processing), and whether async execution adds unnecessary overhead.
 *
 * KEY QUESTION: If audit takes 5ms (same as payment), should we still use
 * CompletableFuture.runAsync()? Or is the threading overhead wasteful?
 *
 * FACTORS TO MEASURE:
 * 1. Thread context switch overhead
 * 2. Executor submission overhead
 * 3. Queue contention under load
 * 4. Total throughput comparison
 *
 * TECHNICAL FACTS:
 * - Context switch: typically 1-10 microseconds (µs)
 * - Executor submit(): adds queue operation + potential thread wake-up
 * - ForkJoinPool task stealing: optimized but still has overhead
 * - Thread creation: ~1ms (but pools reuse threads)
 */
class FastAuditOverheadTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════════

    // Operation durations (both fast)
    static final long PAYMENT_DURATION_MS = 5;
    static final long FAST_AUDIT_DURATION_MS = 5;  // Same as payment!

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 1: MEASURING RAW OVERHEAD
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. Measuring Async Overhead for Fast Operations")
    class AsyncOverheadMeasurementTests {

        @Test
        @DisplayName("Measure: Sync vs Async overhead for 5ms operation")
        void measureSyncVsAsyncOverhead() throws InterruptedException {
            int iterations = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(4);

            // Warm up JIT
            for (int i = 0; i < 100; i++) {
                simulateWork(1);
                CompletableFuture.runAsync(() -> simulateWork(1), executor).join();
            }

            // Measure SYNC execution
            long syncStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                simulateWork(FAST_AUDIT_DURATION_MS);
            }
            long syncDuration = System.nanoTime() - syncStart;
            double syncAvgNs = syncDuration / (double) iterations;
            double syncAvgMs = syncAvgNs / 1_000_000.0;

            // Measure ASYNC execution (submit + join)
            long asyncStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                CompletableFuture.runAsync(() -> simulateWork(FAST_AUDIT_DURATION_MS), executor).join();
            }
            long asyncDuration = System.nanoTime() - asyncStart;
            double asyncAvgNs = asyncDuration / (double) iterations;
            double asyncAvgMs = asyncAvgNs / 1_000_000.0;

            // Calculate overhead
            double overheadNs = asyncAvgNs - syncAvgNs;
            double overheadMs = overheadNs / 1_000_000.0;
            double overheadPercent = (overheadNs / syncAvgNs) * 100;

            System.out.println("═".repeat(70));
            System.out.println("SYNC vs ASYNC OVERHEAD (5ms operation, " + iterations + " iterations)");
            System.out.println("═".repeat(70));
            System.out.printf("Sync avg:     %.3f ms (%.0f ns)%n", syncAvgMs, syncAvgNs);
            System.out.printf("Async avg:    %.3f ms (%.0f ns)%n", asyncAvgMs, asyncAvgNs);
            System.out.printf("Overhead:     %.3f ms (%.0f ns) = %.1f%% of operation time%n",
                overheadMs, overheadNs, overheadPercent);
            System.out.println("═".repeat(70));

            // The overhead should be relatively small for 5ms operations
            // But still measurable
            assertTrue(overheadMs < 2.0,
                "Async overhead should be < 2ms for well-configured executor");

            executor.shutdown();
        }

        @Test
        @DisplayName("Measure: thenRun() vs thenRunAsync() overhead")
        void measureThenRunVsThenRunAsyncOverhead() throws InterruptedException {
            int iterations = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(4);

            // Warm up
            for (int i = 0; i < 100; i++) {
                CompletableFuture.runAsync(() -> simulateWork(1), executor)
                    .thenRun(() -> simulateWork(1)).join();
            }

            // Measure thenRun() - same thread continuation
            long thenRunStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                CompletableFuture.runAsync(() -> simulateWork(PAYMENT_DURATION_MS), executor)
                    .thenRun(() -> simulateWork(FAST_AUDIT_DURATION_MS))
                    .join();
            }
            long thenRunDuration = System.nanoTime() - thenRunStart;
            double thenRunAvgMs = (thenRunDuration / (double) iterations) / 1_000_000.0;

            // Measure thenRunAsync() - different thread
            long thenRunAsyncStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                CompletableFuture.runAsync(() -> simulateWork(PAYMENT_DURATION_MS), executor)
                    .thenRunAsync(() -> simulateWork(FAST_AUDIT_DURATION_MS), executor)
                    .join();
            }
            long thenRunAsyncDuration = System.nanoTime() - thenRunAsyncStart;
            double thenRunAsyncAvgMs = (thenRunAsyncDuration / (double) iterations) / 1_000_000.0;

            double overheadMs = thenRunAsyncAvgMs - thenRunAvgMs;
            double overheadPercent = (overheadMs / thenRunAvgMs) * 100;

            System.out.println("═".repeat(70));
            System.out.println("thenRun() vs thenRunAsync() (5ms + 5ms operations)");
            System.out.println("═".repeat(70));
            System.out.printf("thenRun() avg:      %.3f ms (same thread)%n", thenRunAvgMs);
            System.out.printf("thenRunAsync() avg: %.3f ms (thread switch)%n", thenRunAsyncAvgMs);
            System.out.printf("Thread switch overhead: %.3f ms (%.1f%%)%n", overheadMs, overheadPercent);
            System.out.println("═".repeat(70));

            executor.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 2: THROUGHPUT COMPARISON
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. Throughput Comparison: Fast Payment + Fast Audit")
    class ThroughputComparisonTests {

        @Test
        @DisplayName("Compare: Sync-all vs Fire-and-forget vs Same-thread continuation")
        void compareThroughputStrategies() throws InterruptedException {
            int requestCount = 500;

            record Result(String strategy, long totalTimeMs, double avgLatencyMs, int completed) {}
            List<Result> results = new ArrayList<>();

            // Strategy 1: Everything synchronous (baseline)
            {
                AtomicInteger completed = new AtomicInteger(0);
                AtomicLong totalLatency = new AtomicLong(0);

                long start = System.currentTimeMillis();
                for (int i = 0; i < requestCount; i++) {
                    long opStart = System.nanoTime();

                    // Sync payment
                    simulateWork(PAYMENT_DURATION_MS);
                    // Sync audit (blocks until done)
                    simulateWork(FAST_AUDIT_DURATION_MS);

                    totalLatency.addAndGet(System.nanoTime() - opStart);
                    completed.incrementAndGet();
                }
                long totalTime = System.currentTimeMillis() - start;
                double avgLatency = (totalLatency.get() / (double) requestCount) / 1_000_000.0;

                results.add(new Result("Sync All (sequential)", totalTime, avgLatency, completed.get()));
            }

            // Strategy 2: Fire-and-forget async audit
            {
                ExecutorService auditExecutor = Executors.newFixedThreadPool(4);
                AtomicInteger completed = new AtomicInteger(0);
                AtomicLong totalLatency = new AtomicLong(0);
                List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());

                long start = System.currentTimeMillis();
                for (int i = 0; i < requestCount; i++) {
                    long opStart = System.nanoTime();

                    // Sync payment
                    simulateWork(PAYMENT_DURATION_MS);

                    // Fire-and-forget audit (payment returns immediately)
                    CompletableFuture<Void> auditFuture = CompletableFuture.runAsync(
                        () -> simulateWork(FAST_AUDIT_DURATION_MS),
                        auditExecutor
                    );
                    futures.add(auditFuture);

                    totalLatency.addAndGet(System.nanoTime() - opStart);
                    completed.incrementAndGet();
                }
                long totalTime = System.currentTimeMillis() - start;
                double avgLatency = (totalLatency.get() / (double) requestCount) / 1_000_000.0;

                // Wait for all audits to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                results.add(new Result("Fire-and-forget async", totalTime, avgLatency, completed.get()));
                auditExecutor.shutdown();
            }

            // Strategy 3: Parallel execution (payment and audit in parallel)
            {
                ExecutorService executor = Executors.newFixedThreadPool(8);
                AtomicInteger completed = new AtomicInteger(0);
                AtomicLong totalLatency = new AtomicLong(0);
                CountDownLatch latch = new CountDownLatch(requestCount);

                long start = System.currentTimeMillis();
                for (int i = 0; i < requestCount; i++) {
                    executor.submit(() -> {
                        long opStart = System.nanoTime();

                        // Payment and audit run in parallel
                        CompletableFuture<Void> payment = CompletableFuture.runAsync(
                            () -> simulateWork(PAYMENT_DURATION_MS), executor);
                        CompletableFuture<Void> audit = CompletableFuture.runAsync(
                            () -> simulateWork(FAST_AUDIT_DURATION_MS), executor);

                        CompletableFuture.allOf(payment, audit).join();

                        totalLatency.addAndGet(System.nanoTime() - opStart);
                        completed.incrementAndGet();
                        latch.countDown();
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
                long totalTime = System.currentTimeMillis() - start;
                double avgLatency = (totalLatency.get() / (double) requestCount) / 1_000_000.0;

                results.add(new Result("Parallel (both async)", totalTime, avgLatency, completed.get()));
                executor.shutdown();
            }

            // Strategy 4: Virtual threads
            {
                ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
                AtomicInteger completed = new AtomicInteger(0);
                AtomicLong totalLatency = new AtomicLong(0);
                CountDownLatch latch = new CountDownLatch(requestCount);

                long start = System.currentTimeMillis();
                for (int i = 0; i < requestCount; i++) {
                    virtualExecutor.submit(() -> {
                        long opStart = System.nanoTime();

                        // Sync payment
                        simulateWork(PAYMENT_DURATION_MS);
                        // Fire-and-forget audit on virtual thread
                        CompletableFuture.runAsync(
                            () -> simulateWork(FAST_AUDIT_DURATION_MS),
                            virtualExecutor
                        );

                        totalLatency.addAndGet(System.nanoTime() - opStart);
                        completed.incrementAndGet();
                        latch.countDown();
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
                long totalTime = System.currentTimeMillis() - start;
                double avgLatency = (totalLatency.get() / (double) requestCount) / 1_000_000.0;

                results.add(new Result("Virtual threads", totalTime, avgLatency, completed.get()));
                virtualExecutor.shutdown();
            }

            // Print comparison
            System.out.println("\n" + "═".repeat(80));
            System.out.println("THROUGHPUT COMPARISON: Fast Payment (5ms) + Fast Audit (5ms)");
            System.out.println("═".repeat(80));
            System.out.printf("%-25s | %12s | %15s | %10s%n",
                "Strategy", "Total Time", "Avg Latency", "Completed");
            System.out.println("-".repeat(80));

            for (Result r : results) {
                System.out.printf("%-25s | %10d ms | %12.3f ms | %10d%n",
                    r.strategy, r.totalTimeMs, r.avgLatencyMs, r.completed);
            }
            System.out.println("═".repeat(80));

            // Analysis
            Result syncAll = results.get(0);
            Result fireAndForget = results.get(1);

            System.out.println("\nANALYSIS:");
            System.out.printf("- Fire-and-forget latency improvement: %.1f%% faster response%n",
                ((syncAll.avgLatencyMs - fireAndForget.avgLatencyMs) / syncAll.avgLatencyMs) * 100);
            System.out.printf("- Fire-and-forget avg latency: %.3f ms (vs sync: %.3f ms)%n",
                fireAndForget.avgLatencyMs, syncAll.avgLatencyMs);
        }

        @Test
        @DisplayName("When does async overhead exceed benefits?")
        void whenDoesAsyncOverheadExceedBenefits() throws InterruptedException {
            System.out.println("\n" + "═".repeat(80));
            System.out.println("ASYNC OVERHEAD vs OPERATION DURATION ANALYSIS");
            System.out.println("═".repeat(80));

            ExecutorService executor = Executors.newFixedThreadPool(4);
            int iterations = 500;

            // Test different operation durations
            long[] durations = {1, 2, 5, 10, 20, 50, 100};

            System.out.printf("%-12s | %12s | %12s | %12s | %8s%n",
                "Op Duration", "Sync Time", "Async Time", "Overhead", "Worth it?");
            System.out.println("-".repeat(80));

            for (long duration : durations) {
                // Measure sync
                long syncStart = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    simulateWork(duration);
                }
                double syncAvgMs = ((System.nanoTime() - syncStart) / (double) iterations) / 1_000_000.0;

                // Measure async (fire-and-forget, no join)
                long asyncStart = System.nanoTime();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (int i = 0; i < iterations; i++) {
                    futures.add(CompletableFuture.runAsync(() -> simulateWork(duration), executor));
                }
                // Measure time to SUBMIT (not complete)
                double asyncSubmitAvgMs = ((System.nanoTime() - asyncStart) / (double) iterations) / 1_000_000.0;

                // Wait for completion
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                double overheadMs = asyncSubmitAvgMs;  // Submit time is pure overhead
                boolean worthIt = duration > overheadMs * 2;  // Worth if operation > 2x overhead

                System.out.printf("%10d ms | %10.3f ms | %10.3f ms | %10.3f ms | %8s%n",
                    duration, syncAvgMs, asyncSubmitAvgMs, overheadMs,
                    worthIt ? "YES" : "NO");
            }

            System.out.println("═".repeat(80));
            System.out.println("\nCONCLUSION:");
            System.out.println("- For very fast operations (< 2ms), async overhead may exceed benefits");
            System.out.println("- For operations >= 5ms, async fire-and-forget improves response time");
            System.out.println("- The 'break-even' point depends on your executor and system load");

            executor.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 3: WHEN TO USE ASYNC FOR FAST OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. Decision Guide: When to Use Async for Fast Operations")
    class DecisionGuideTests {

        @Test
        @DisplayName("Scenario: Fast audit that MUST complete (use sync)")
        void fastAuditMustComplete_useSync() {
            AtomicInteger auditsCompleted = new AtomicInteger(0);

            // If audit MUST complete before response, sync is simpler
            long start = System.nanoTime();

            // Payment
            simulateWork(PAYMENT_DURATION_MS);
            // Audit (must complete)
            simulateWork(FAST_AUDIT_DURATION_MS);
            auditsCompleted.incrementAndGet();

            long durationMs = (System.nanoTime() - start) / 1_000_000;

            System.out.println("═".repeat(60));
            System.out.println("SCENARIO: Audit MUST complete before response");
            System.out.println("═".repeat(60));
            System.out.printf("Total time: %d ms (payment %d + audit %d)%n",
                durationMs, PAYMENT_DURATION_MS, FAST_AUDIT_DURATION_MS);
            System.out.println("RECOMMENDATION: Use SYNC - simpler, no thread overhead");
            System.out.println("═".repeat(60));

            assertEquals(1, auditsCompleted.get());
        }

        @Test
        @DisplayName("Scenario: Fast audit is best-effort (use async)")
        void fastAuditBestEffort_useAsync() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            AtomicInteger paymentsCompleted = new AtomicInteger(0);

            // If audit is best-effort, async improves response time
            long start = System.nanoTime();

            // Payment (response can return after this)
            simulateWork(PAYMENT_DURATION_MS);
            paymentsCompleted.incrementAndGet();

            // Fire-and-forget audit (response already sent)
            CompletableFuture.runAsync(() -> simulateWork(FAST_AUDIT_DURATION_MS), executor);

            long responseTimeMs = (System.nanoTime() - start) / 1_000_000;

            // Wait for audit to complete
            Thread.sleep(FAST_AUDIT_DURATION_MS + 50);

            System.out.println("═".repeat(60));
            System.out.println("SCENARIO: Audit is best-effort (fire-and-forget)");
            System.out.println("═".repeat(60));
            System.out.printf("Response time: %d ms (only payment)%n", responseTimeMs);
            System.out.printf("Improvement: %d ms faster than sync%n", FAST_AUDIT_DURATION_MS);
            System.out.println("RECOMMENDATION: Use ASYNC - better response time");
            System.out.println("═".repeat(60));

            assertEquals(1, paymentsCompleted.get());
            assertTrue(responseTimeMs < PAYMENT_DURATION_MS + FAST_AUDIT_DURATION_MS);

            executor.shutdown();
        }

        @Test
        @DisplayName("Decision matrix summary")
        void decisionMatrixSummary() {
            System.out.println("\n" + "═".repeat(80));
            System.out.println("DECISION MATRIX: When to use async for FAST operations (< 10ms)");
            System.out.println("═".repeat(80));
            System.out.println();
            System.out.println("| Scenario                          | Recommendation | Reason                    |");
            System.out.println("|-----------------------------------|----------------|---------------------------|");
            System.out.println("| Audit MUST complete               | SYNC           | Simpler, no overhead      |");
            System.out.println("| Audit is best-effort              | ASYNC          | Better response time      |");
            System.out.println("| Audit failure must be handled     | SYNC or WAIT   | Need to catch exceptions  |");
            System.out.println("| High throughput, low latency      | ASYNC          | Parallel execution        |");
            System.out.println("| Simple CRUD, low traffic          | SYNC           | Overhead not worth it     |");
            System.out.println("| Operation < 1ms                   | SYNC           | Overhead exceeds benefit  |");
            System.out.println("| Operation > 5ms, high traffic     | ASYNC          | Significant improvement   |");
            System.out.println();
            System.out.println("KEY INSIGHT: For FAST operations, the decision depends on:");
            System.out.println("1. Whether you need to wait for completion");
            System.out.println("2. How many concurrent requests you handle");
            System.out.println("3. Whether the threading overhead is acceptable");
            System.out.println("═".repeat(80));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private static void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
