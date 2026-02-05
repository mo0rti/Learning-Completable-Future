package com.mortitech.completablefuture.level6_advanced;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PAYMENT + AUDIT THREADING TESTS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * These tests demonstrate the threading challenges when:
 * 1. A synchronous method (processPayment) calls an async method (logAuditAsync)
 * 2. Both operations compete for thread pool resources
 * 3. High load causes thread pool exhaustion
 *
 * SCENARIO:
 * - Web server receives payment requests (uses request threads)
 * - Each payment triggers an async audit log (uses CompletableFuture threads)
 * - Under high load, audit threads can exhaust the pool
 *
 * SOLUTIONS TESTED:
 * 1. Shared Executor (PROBLEM) - demonstrates starvation
 * 2. Separate Executors (SOLUTION) - isolation prevents starvation
 * 3. Virtual Threads (SOLUTION) - scales without traditional thread limits
 * 4. Same-Thread Execution (SOLUTION) - for fast operations
 */
class PaymentAuditThreadingTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // SIMULATED SERVICES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Simulated Payment Service that demonstrates the threading problem.
     */
    static class PaymentService {
        private final ExecutorService auditExecutor;
        private final AtomicInteger paymentsProcessed = new AtomicInteger(0);
        private final AtomicInteger auditsCompleted = new AtomicInteger(0);
        private final AtomicInteger auditsRejected = new AtomicInteger(0);
        private final long auditLatencyMs;

        public PaymentService(ExecutorService auditExecutor, long auditLatencyMs) {
            this.auditExecutor = auditExecutor;
            this.auditLatencyMs = auditLatencyMs;
        }

        /**
         * Process payment (fast) and trigger async audit (potentially slow).
         */
        public void processPayment(String userId, BigDecimal amount) {
            // Simulate fast payment processing (5ms)
            simulateWork(5);
            paymentsProcessed.incrementAndGet();

            // Fire-and-forget audit
            logAuditAsync(userId, amount);
        }

        /**
         * Async audit that returns CompletableFuture.
         * Uses the provided executor.
         */
        public CompletableFuture<Void> logAuditAsync(String userId, BigDecimal amount) {
            try {
                return CompletableFuture.runAsync(() -> {
                    // Simulate slow DB write
                    simulateWork(auditLatencyMs);
                    auditsCompleted.incrementAndGet();
                }, auditExecutor);
            } catch (RejectedExecutionException e) {
                auditsRejected.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        }

        public int getPaymentsProcessed() { return paymentsProcessed.get(); }
        public int getAuditsCompleted() { return auditsCompleted.get(); }
        public int getAuditsRejected() { return auditsRejected.get(); }

        private void simulateWork(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 1: THE PROBLEM - SHARED EXECUTOR STARVATION
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. THE PROBLEM: Shared Executor Starvation")
    class SharedExecutorProblemTests {

        @Test
        @DisplayName("Shared executor: slow audits block payment processing")
        void sharedExecutor_slowAuditsBlockPayments() throws InterruptedException {
            // Given: A SHARED executor for both payments and audits
            // Small pool to demonstrate the problem quickly
            ExecutorService sharedExecutor = new ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure
            );

            // Audit takes 100ms (slow DB write)
            PaymentService service = new PaymentService(sharedExecutor, 100);

            // When: 20 concurrent payment requests arrive
            int requestCount = 20;
            CountDownLatch requestsStarted = new CountDownLatch(requestCount);
            CountDownLatch requestsCompleted = new CountDownLatch(requestCount);
            AtomicLong totalPaymentTime = new AtomicLong(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                sharedExecutor.submit(() -> {
                    requestsStarted.countDown();
                    long paymentStart = System.currentTimeMillis();

                    service.processPayment("user" + userId, BigDecimal.TEN);

                    totalPaymentTime.addAndGet(System.currentTimeMillis() - paymentStart);
                    requestsCompleted.countDown();
                });
            }

            // Wait for all requests to complete
            assertTrue(requestsCompleted.await(10, TimeUnit.SECONDS), "Requests should complete");

            long totalTime = System.currentTimeMillis() - startTime;
            double avgPaymentTime = totalPaymentTime.get() / (double) requestCount;

            // Then: Payments are SLOW because they're blocked by audits
            System.out.println("=== SHARED EXECUTOR (PROBLEM) ===");
            System.out.printf("Total time: %dms%n", totalTime);
            System.out.printf("Avg payment time: %.1fms (should be ~5ms if not blocked)%n", avgPaymentTime);
            System.out.printf("Payments processed: %d%n", service.getPaymentsProcessed());
            System.out.printf("Audits completed: %d%n", service.getAuditsCompleted());

            // Payment should be fast (~5ms) but is blocked by slow audits
            // With CallerRunsPolicy, the caller thread runs the audit, blocking the payment
            assertTrue(avgPaymentTime > 20,
                "Payments should be slow due to shared executor contention. Avg: " + avgPaymentTime + "ms");

            sharedExecutor.shutdownNow();
        }

        @Test
        @DisplayName("Shared executor with AbortPolicy: audits get rejected under load")
        void sharedExecutor_abortPolicy_auditsRejected() throws InterruptedException {
            // Given: Shared executor with AbortPolicy (throws on rejection)
            ExecutorService sharedExecutor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(5),
                new ThreadPoolExecutor.AbortPolicy()
            );

            // Audit takes 200ms (very slow)
            PaymentService service = new PaymentService(sharedExecutor, 200);

            // When: Burst of payment requests
            int requestCount = 20;
            CountDownLatch latch = new CountDownLatch(requestCount);

            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                CompletableFuture.runAsync(() -> {
                    try {
                        service.processPayment("user" + userId, BigDecimal.TEN);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            Thread.sleep(500); // Allow some audits to complete

            // Then: Many audits were rejected
            System.out.println("=== SHARED EXECUTOR WITH ABORT POLICY ===");
            System.out.printf("Payments processed: %d%n", service.getPaymentsProcessed());
            System.out.printf("Audits completed: %d%n", service.getAuditsCompleted());
            System.out.printf("Audits REJECTED: %d%n", service.getAuditsRejected());

            assertTrue(service.getAuditsRejected() > 0,
                "Some audits should be rejected due to queue overflow");

            sharedExecutor.shutdownNow();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 2: SOLUTION - SEPARATE EXECUTORS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. SOLUTION: Separate Executors")
    class SeparateExecutorsSolutionTests {

        @Test
        @DisplayName("Separate executors: payments are fast, audits don't block")
        void separateExecutors_paymentsNotBlockedByAudits() throws InterruptedException {
            // Given: SEPARATE executors for payments and audits
            ExecutorService paymentExecutor = Executors.newFixedThreadPool(4);
            ExecutorService auditExecutor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );

            // Audit still takes 100ms (slow)
            PaymentService service = new PaymentService(auditExecutor, 100);

            // When: 20 concurrent payment requests
            int requestCount = 20;
            CountDownLatch requestsCompleted = new CountDownLatch(requestCount);
            AtomicLong totalPaymentTime = new AtomicLong(0);
            List<Long> paymentTimes = Collections.synchronizedList(new ArrayList<>());

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                paymentExecutor.submit(() -> {
                    long paymentStart = System.currentTimeMillis();

                    service.processPayment("user" + userId, BigDecimal.TEN);

                    long paymentTime = System.currentTimeMillis() - paymentStart;
                    paymentTimes.add(paymentTime);
                    totalPaymentTime.addAndGet(paymentTime);
                    requestsCompleted.countDown();
                });
            }

            assertTrue(requestsCompleted.await(5, TimeUnit.SECONDS), "Requests should complete");

            long totalTime = System.currentTimeMillis() - startTime;
            double avgPaymentTime = totalPaymentTime.get() / (double) requestCount;

            // Wait for audits to complete
            Thread.sleep(500);

            // Then: Payments are FAST because they're not blocked by audits
            System.out.println("=== SEPARATE EXECUTORS (SOLUTION) ===");
            System.out.printf("Total time: %dms%n", totalTime);
            System.out.printf("Avg payment time: %.1fms (should be ~5-10ms)%n", avgPaymentTime);
            System.out.printf("Payments processed: %d%n", service.getPaymentsProcessed());
            System.out.printf("Audits completed: %d%n", service.getAuditsCompleted());

            // Payments should be fast (~5ms + minimal overhead)
            assertTrue(avgPaymentTime < 50,
                "Payments should be fast with separate executor. Avg: " + avgPaymentTime + "ms");

            assertEquals(requestCount, service.getPaymentsProcessed(),
                "All payments should complete");

            paymentExecutor.shutdownNow();
            auditExecutor.shutdownNow();
        }

        @Test
        @DisplayName("Separate executors: audit failures don't affect payments")
        void separateExecutors_auditFailuresDontAffectPayments() throws InterruptedException {
            // Given: Separate executors, audit executor has tiny capacity
            ExecutorService paymentExecutor = Executors.newFixedThreadPool(4);
            ExecutorService auditExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2),
                new ThreadPoolExecutor.AbortPolicy() // Will reject
            );

            PaymentService service = new PaymentService(auditExecutor, 500); // Very slow audits

            // When: Burst of payments (audits will overflow)
            int requestCount = 20;
            CountDownLatch requestsCompleted = new CountDownLatch(requestCount);

            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                paymentExecutor.submit(() -> {
                    service.processPayment("user" + userId, BigDecimal.TEN);
                    requestsCompleted.countDown();
                });
            }

            assertTrue(requestsCompleted.await(2, TimeUnit.SECONDS), "Payments should complete quickly");

            // Then: ALL payments succeed, even though audits are rejected
            System.out.println("=== SEPARATE EXECUTORS - AUDIT FAILURE ISOLATION ===");
            System.out.printf("Payments processed: %d (should be %d)%n",
                service.getPaymentsProcessed(), requestCount);
            System.out.printf("Audits rejected: %d (audit executor overwhelmed)%n",
                service.getAuditsRejected());

            assertEquals(requestCount, service.getPaymentsProcessed(),
                "All payments should succeed regardless of audit status");

            paymentExecutor.shutdownNow();
            auditExecutor.shutdownNow();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 3: SOLUTION - VIRTUAL THREADS (Java 21+)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. SOLUTION: Virtual Threads (Java 21+)")
    class VirtualThreadsSolutionTests {

        @Test
        @DisplayName("Virtual threads: scale to thousands without exhaustion")
        void virtualThreads_scaleWithoutExhaustion() throws InterruptedException {
            // Given: Virtual thread executor (unlimited scaling)
            ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

            PaymentService service = new PaymentService(virtualExecutor, 50); // 50ms audit

            // When: 1000 concurrent payment requests (would exhaust traditional pool)
            int requestCount = 1000;
            CountDownLatch requestsCompleted = new CountDownLatch(requestCount);
            AtomicLong totalPaymentTime = new AtomicLong(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                virtualExecutor.submit(() -> {
                    long paymentStart = System.currentTimeMillis();
                    service.processPayment("user" + userId, BigDecimal.TEN);
                    totalPaymentTime.addAndGet(System.currentTimeMillis() - paymentStart);
                    requestsCompleted.countDown();
                });
            }

            assertTrue(requestsCompleted.await(30, TimeUnit.SECONDS), "Requests should complete");

            // Wait for audits
            Thread.sleep(1000);

            long totalTime = System.currentTimeMillis() - startTime;
            double avgPaymentTime = totalPaymentTime.get() / (double) requestCount;

            // Then: High throughput without thread exhaustion
            System.out.println("=== VIRTUAL THREADS (SOLUTION) ===");
            System.out.printf("Total time for %d requests: %dms%n", requestCount, totalTime);
            System.out.printf("Avg payment time: %.1fms%n", avgPaymentTime);
            System.out.printf("Payments processed: %d%n", service.getPaymentsProcessed());
            System.out.printf("Audits completed: %d%n", service.getAuditsCompleted());
            System.out.printf("Audits rejected: %d (should be 0)%n", service.getAuditsRejected());

            assertEquals(requestCount, service.getPaymentsProcessed(),
                "All payments should complete");
            assertEquals(0, service.getAuditsRejected(),
                "No audits should be rejected with virtual threads");

            virtualExecutor.shutdownNow();
        }

        @Test
        @DisplayName("Virtual threads vs Fixed pool: throughput comparison")
        void virtualThreads_throughputComparison() throws InterruptedException {
            int requestCount = 500;
            long auditLatency = 20; // 20ms per audit

            // Scenario 1: Fixed thread pool (limited)
            ExecutorService fixedPool = new ThreadPoolExecutor(
                8, 8, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );

            PaymentService fixedService = new PaymentService(fixedPool, auditLatency);
            long fixedStart = System.currentTimeMillis();

            CountDownLatch fixedLatch = new CountDownLatch(requestCount);
            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                CompletableFuture.runAsync(() -> {
                    fixedService.processPayment("user" + userId, BigDecimal.TEN);
                    fixedLatch.countDown();
                }, fixedPool);
            }
            fixedLatch.await(30, TimeUnit.SECONDS);
            long fixedTime = System.currentTimeMillis() - fixedStart;

            // Scenario 2: Virtual threads (unlimited)
            ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();
            PaymentService virtualService = new PaymentService(virtualPool, auditLatency);
            long virtualStart = System.currentTimeMillis();

            CountDownLatch virtualLatch = new CountDownLatch(requestCount);
            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                virtualPool.submit(() -> {
                    virtualService.processPayment("user" + userId, BigDecimal.TEN);
                    virtualLatch.countDown();
                });
            }
            virtualLatch.await(30, TimeUnit.SECONDS);
            long virtualTime = System.currentTimeMillis() - virtualStart;

            // Results
            System.out.println("=== THROUGHPUT COMPARISON ===");
            System.out.printf("Fixed Pool (8 threads): %dms for %d requests%n", fixedTime, requestCount);
            System.out.printf("Virtual Threads: %dms for %d requests%n", virtualTime, requestCount);
            System.out.printf("Virtual threads speedup: %.1fx%n", (double) fixedTime / virtualTime);

            // Virtual threads should be faster for I/O-bound work
            assertTrue(virtualTime <= fixedTime,
                "Virtual threads should be at least as fast as fixed pool");

            fixedPool.shutdownNow();
            virtualPool.shutdownNow();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 4: SOLUTION - SAME THREAD EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. SOLUTION: Same Thread Execution (for fast operations)")
    class SameThreadExecutionTests {

        /**
         * Alternative service that runs audit on the SAME thread if it's fast.
         */
        static class SameThreadPaymentService {
            private final AtomicInteger paymentsProcessed = new AtomicInteger(0);
            private final AtomicInteger auditsCompleted = new AtomicInteger(0);
            private final long auditLatencyMs;

            public SameThreadPaymentService(long auditLatencyMs) {
                this.auditLatencyMs = auditLatencyMs;
            }

            /**
             * Process payment and audit on the SAME thread.
             * Good for fast audits where thread switching overhead > audit time.
             */
            public void processPaymentWithSyncAudit(String userId, BigDecimal amount) {
                // Payment processing
                simulateWork(5);
                paymentsProcessed.incrementAndGet();

                // Sync audit (same thread) - no thread switching overhead
                simulateWork(auditLatencyMs);
                auditsCompleted.incrementAndGet();
            }

            /**
             * Process payment, then chain audit using thenRun() (same thread continuation).
             */
            public CompletableFuture<Void> processPaymentWithChainedAudit(
                    String userId, BigDecimal amount, ExecutorService executor) {

                return CompletableFuture.runAsync(() -> {
                    // Payment processing
                    simulateWork(5);
                    paymentsProcessed.incrementAndGet();
                }, executor).thenRun(() -> {
                    // Audit runs on SAME thread (no thenRunAsync)
                    simulateWork(auditLatencyMs);
                    auditsCompleted.incrementAndGet();
                });
            }

            public int getPaymentsProcessed() { return paymentsProcessed.get(); }
            public int getAuditsCompleted() { return auditsCompleted.get(); }

            private void simulateWork(long millis) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Test
        @DisplayName("Same thread: no thread overhead for fast audits")
        void sameThread_noOverheadForFastAudits() throws InterruptedException {
            // Given: Fast audit (2ms) - thread switching overhead > audit time
            SameThreadPaymentService service = new SameThreadPaymentService(2);
            ExecutorService executor = Executors.newFixedThreadPool(4);

            int requestCount = 100;
            CountDownLatch latch = new CountDownLatch(requestCount);
            AtomicLong totalTime = new AtomicLong(0);

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < requestCount; i++) {
                final int userId = i;
                executor.submit(() -> {
                    long opStart = System.currentTimeMillis();
                    service.processPaymentWithSyncAudit("user" + userId, BigDecimal.TEN);
                    totalTime.addAndGet(System.currentTimeMillis() - opStart);
                    latch.countDown();
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Should complete");

            long elapsed = System.currentTimeMillis() - startTime;
            double avgTime = totalTime.get() / (double) requestCount;

            System.out.println("=== SAME THREAD EXECUTION ===");
            System.out.printf("Total time: %dms for %d requests%n", elapsed, requestCount);
            System.out.printf("Avg operation time: %.1fms (payment 5ms + audit 2ms = 7ms)%n", avgTime);
            System.out.printf("Payments: %d, Audits: %d%n",
                service.getPaymentsProcessed(), service.getAuditsCompleted());

            // Should be close to 7ms (5ms payment + 2ms audit)
            assertTrue(avgTime < 15,
                "Same thread should have minimal overhead. Avg: " + avgTime + "ms");

            executor.shutdownNow();
        }

        @Test
        @DisplayName("thenRun() vs thenRunAsync(): thread usage comparison")
        void thenRun_vs_thenRunAsync_threadComparison() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

            // thenRun() - runs on SAME thread
            CompletableFuture.runAsync(() -> {
                threadNames.add("Main: " + Thread.currentThread().getName());
            }, executor).thenRun(() -> {
                threadNames.add("thenRun: " + Thread.currentThread().getName());
            }).join();

            // thenRunAsync() - may run on DIFFERENT thread
            CompletableFuture.runAsync(() -> {
                threadNames.add("Main2: " + Thread.currentThread().getName());
            }, executor).thenRunAsync(() -> {
                threadNames.add("thenRunAsync: " + Thread.currentThread().getName());
            }, executor).join();

            System.out.println("=== thenRun vs thenRunAsync ===");
            threadNames.forEach(System.out::println);

            // thenRun should use the same thread as the preceding stage
            // thenRunAsync may use a different thread from the pool

            executor.shutdownNow();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 5: COMPREHENSIVE COMPARISON
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. Comprehensive Comparison")
    class ComprehensiveComparisonTests {

        @Test
        @DisplayName("All strategies compared under load")
        void allStrategiesCompared() throws InterruptedException {
            int requestCount = 200;
            long auditLatency = 30;

            record Result(String strategy, long timeMs, int paymentsCompleted, int auditsCompleted, int auditsRejected) {}
            List<Result> results = new ArrayList<>();

            // Strategy 1: Shared Executor (Problem)
            {
                ExecutorService shared = new ThreadPoolExecutor(
                    4, 4, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(20),
                    new ThreadPoolExecutor.CallerRunsPolicy()
                );
                PaymentService service = new PaymentService(shared, auditLatency);

                long start = System.currentTimeMillis();
                CountDownLatch latch = new CountDownLatch(requestCount);

                for (int i = 0; i < requestCount; i++) {
                    final int id = i;
                    shared.submit(() -> {
                        service.processPayment("user" + id, BigDecimal.TEN);
                        latch.countDown();
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
                Thread.sleep(500);

                results.add(new Result("Shared Executor",
                    System.currentTimeMillis() - start,
                    service.getPaymentsProcessed(),
                    service.getAuditsCompleted(),
                    service.getAuditsRejected()));

                shared.shutdownNow();
            }

            // Strategy 2: Separate Executors (Solution)
            {
                ExecutorService paymentExec = Executors.newFixedThreadPool(4);
                ExecutorService auditExec = new ThreadPoolExecutor(
                    4, 4, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(200),
                    new ThreadPoolExecutor.CallerRunsPolicy()
                );
                PaymentService service = new PaymentService(auditExec, auditLatency);

                long start = System.currentTimeMillis();
                CountDownLatch latch = new CountDownLatch(requestCount);

                for (int i = 0; i < requestCount; i++) {
                    final int id = i;
                    paymentExec.submit(() -> {
                        service.processPayment("user" + id, BigDecimal.TEN);
                        latch.countDown();
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
                Thread.sleep(500);

                results.add(new Result("Separate Executors",
                    System.currentTimeMillis() - start,
                    service.getPaymentsProcessed(),
                    service.getAuditsCompleted(),
                    service.getAuditsRejected()));

                paymentExec.shutdownNow();
                auditExec.shutdownNow();
            }

            // Strategy 3: Virtual Threads (Solution)
            {
                ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
                PaymentService service = new PaymentService(virtual, auditLatency);

                long start = System.currentTimeMillis();
                CountDownLatch latch = new CountDownLatch(requestCount);

                for (int i = 0; i < requestCount; i++) {
                    final int id = i;
                    virtual.submit(() -> {
                        service.processPayment("user" + id, BigDecimal.TEN);
                        latch.countDown();
                    });
                }
                latch.await(30, TimeUnit.SECONDS);
                Thread.sleep(500);

                results.add(new Result("Virtual Threads",
                    System.currentTimeMillis() - start,
                    service.getPaymentsProcessed(),
                    service.getAuditsCompleted(),
                    service.getAuditsRejected()));

                virtual.shutdownNow();
            }

            // Print comparison
            System.out.println("\n" + "═".repeat(80));
            System.out.println("STRATEGY COMPARISON (" + requestCount + " requests, " + auditLatency + "ms audit latency)");
            System.out.println("═".repeat(80));
            System.out.printf("%-20s | %8s | %10s | %10s | %10s%n",
                "Strategy", "Time(ms)", "Payments", "Audits OK", "Audits Rej");
            System.out.println("-".repeat(80));

            for (Result r : results) {
                System.out.printf("%-20s | %8d | %10d | %10d | %10d%n",
                    r.strategy, r.timeMs, r.paymentsCompleted, r.auditsCompleted, r.auditsRejected);
            }
            System.out.println("═".repeat(80));

            // Assertions
            Result shared = results.get(0);
            Result separate = results.get(1);
            Result virtual = results.get(2);

            // All should complete all payments
            assertEquals(requestCount, shared.paymentsCompleted);
            assertEquals(requestCount, separate.paymentsCompleted);
            assertEquals(requestCount, virtual.paymentsCompleted);

            // Virtual threads should be fastest or comparable
            assertTrue(virtual.timeMs <= shared.timeMs * 1.5,
                "Virtual threads should not be significantly slower than shared");
        }
    }
}
