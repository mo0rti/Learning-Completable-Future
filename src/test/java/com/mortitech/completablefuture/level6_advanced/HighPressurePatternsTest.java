package com.mortitech.completablefuture.level6_advanced;

import com.mortitech.completablefuture.level6_advanced.HighPressurePatterns.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for High Pressure Patterns.
 *
 * These tests demonstrate:
 * 1. Thread pool exhaustion under load
 * 2. Different rejection policies
 * 3. Batching effectiveness
 * 4. Rate limiting under pressure
 * 5. Circuit breaker behavior
 */
class HighPressurePatternsTest {

    private HighPressurePatterns patterns;

    @BeforeEach
    void setUp() {
        patterns = new HighPressurePatterns();
        patterns.resetMetrics();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 1: THREAD POOL EXHAUSTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Thread Pool Exhaustion")
    class ThreadPoolExhaustionTests {

        @Test
        @DisplayName("AbortPolicy throws when queue is full")
        void testAbortPolicyThrows() {
            // Given: tiny pool with tiny queue
            ExecutorService executor = patterns.createPoolWithAbortPolicy(1, 2);

            // Block the only thread
            CountDownLatch blockingLatch = new CountDownLatch(1);
            executor.submit(() -> {
                try {
                    blockingLatch.await(); // Block forever
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Fill the queue (capacity 2)
            executor.submit(() -> {});
            executor.submit(() -> {});

            // When: submitting one more task
            // Then: RejectedExecutionException is thrown
            assertThrows(RejectedExecutionException.class, () -> {
                executor.submit(() -> {});
            });

            // Cleanup
            blockingLatch.countDown();
            executor.shutdownNow();
        }

        @Test
        @DisplayName("CallerRunsPolicy provides backpressure")
        void testCallerRunsPolicyBackpressure() throws InterruptedException {
            // Given: tiny pool that will overflow
            ExecutorService executor = patterns.createPoolWithCallerRunsPolicy(1, 2);
            AtomicInteger executedOnCallerThread = new AtomicInteger(0);
            String callerThreadName = Thread.currentThread().getName();

            // Block the worker thread
            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch taskStarted = new CountDownLatch(1);

            executor.submit(() -> {
                taskStarted.countDown();
                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            taskStarted.await(); // Wait for blocking task to start

            // Fill the queue
            executor.submit(() -> {});
            executor.submit(() -> {});

            // When: submitting more tasks (will run on caller thread)
            executor.submit(() -> {
                if (Thread.currentThread().getName().equals(callerThreadName)) {
                    executedOnCallerThread.incrementAndGet();
                }
            });

            // Then: task ran on caller thread (backpressure)
            assertEquals(1, executedOnCallerThread.get(),
                "Task should run on caller thread when pool is exhausted");

            // Cleanup
            blockLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("Custom rejection policy tracks rejections")
        void testMonitoredRejectionPolicy() throws InterruptedException {
            // Given: pool with monitored rejection
            ExecutorService executor = patterns.createPoolWithMonitoredRejection(1, 1);

            // Block the worker
            CountDownLatch blockLatch = new CountDownLatch(1);
            executor.submit(() -> {
                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Fill queue
            executor.submit(() -> {});

            // When: overflow occurs
            executor.submit(() -> {});
            executor.submit(() -> {});

            // Then: rejections are tracked
            // Note: with CallerRuns fallback in our policy, they still execute
            assertTrue(patterns.getTasksRejected() >= 1,
                "Should track at least one rejection");

            // Cleanup
            blockLatch.countDown();
            executor.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 2: BATCHING TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Batched Event Logging")
    class BatchingTests {

        @Test
        @DisplayName("Events are batched before writing")
        void testEventBatching() throws InterruptedException {
            // Given: batch logger with batch size 5
            ExecutorService executor = Executors.newFixedThreadPool(2);
            List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch writeLatch = new CountDownLatch(2); // Expect 2 batches

            var logger = patterns.new BatchingEventLogger(
                100,  // buffer capacity
                5,    // batch size
                1000, // max delay (won't trigger in this test)
                executor,
                batch -> {
                    batchSizes.add(batch.size());
                    writeLatch.countDown();
                }
            );

            // When: submitting 10 events (should create 2 batches of 5)
            for (int i = 0; i < 10; i++) {
                logger.logEvent(new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
            }

            // Then: events are batched
            assertTrue(writeLatch.await(2, TimeUnit.SECONDS), "Batches should be written");

            // Verify batch sizes
            Thread.sleep(100); // Allow async completion
            assertTrue(batchSizes.stream().allMatch(size -> size == 5),
                "Each batch should have 5 events, got: " + batchSizes);

            // Cleanup
            logger.shutdown();
            executor.shutdown();
        }

        @Test
        @DisplayName("Buffer full returns false (backpressure signal)")
        void testBufferFullBackpressure() {
            // Given: logger with tiny buffer
            ExecutorService executor = Executors.newFixedThreadPool(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            var logger = patterns.new BatchingEventLogger(
                5,     // tiny buffer
                10,    // batch size larger than buffer (won't auto-flush)
                10000, // long delay
                executor,
                batch -> {
                    try {
                        blockLatch.await(); // Block the writer
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            );

            // When: filling the buffer beyond capacity
            int accepted = 0;
            int rejected = 0;

            for (int i = 0; i < 20; i++) {
                boolean result = logger.logEvent(
                    new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
                if (result) accepted++;
                else rejected++;
            }

            // Then: some events were rejected (buffer full)
            assertTrue(rejected > 0, "Should reject events when buffer is full");
            assertEquals(5, accepted, "Should accept exactly buffer capacity");

            // Cleanup
            blockLatch.countDown();
            logger.shutdown();
            executor.shutdownNow();
        }

        @Test
        @DisplayName("High throughput: batching is more efficient than individual writes")
        void testBatchingEfficiency() throws InterruptedException {
            // Given: counters for writes
            AtomicInteger individualWrites = new AtomicInteger(0);
            AtomicInteger batchedWrites = new AtomicInteger(0);
            int eventCount = 100;

            ExecutorService executor = Executors.newFixedThreadPool(4);

            // Scenario 1: Individual writes (naive approach)
            long naiveStart = System.currentTimeMillis();
            CountDownLatch naiveLatch = new CountDownLatch(eventCount);

            for (int i = 0; i < eventCount; i++) {
                executor.submit(() -> {
                    individualWrites.incrementAndGet();
                    try {
                        Thread.sleep(5); // Simulate DB write latency
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    naiveLatch.countDown();
                });
            }

            naiveLatch.await(30, TimeUnit.SECONDS);
            long naiveDuration = System.currentTimeMillis() - naiveStart;

            // Scenario 2: Batched writes
            CountDownLatch batchLatch = new CountDownLatch(10); // 100 events / 10 batch size = 10 batches
            long batchStart = System.currentTimeMillis();

            var logger = patterns.new BatchingEventLogger(
                200,   // buffer
                10,    // batch size
                100,   // max delay
                executor,
                batch -> {
                    batchedWrites.incrementAndGet();
                    try {
                        Thread.sleep(5); // Same latency, but for whole batch
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    batchLatch.countDown();
                }
            );

            for (int i = 0; i < eventCount; i++) {
                logger.logEvent(new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
            }

            batchLatch.await(5, TimeUnit.SECONDS);
            long batchDuration = System.currentTimeMillis() - batchStart;

            // Then: batching should be faster and use fewer writes
            System.out.printf("Individual: %d writes in %dms%n", individualWrites.get(), naiveDuration);
            System.out.printf("Batched: %d writes in %dms%n", batchedWrites.get(), batchDuration);

            assertTrue(batchedWrites.get() < individualWrites.get(),
                "Batching should require fewer DB writes");
            assertTrue(batchDuration < naiveDuration,
                "Batching should be faster");

            // Cleanup
            logger.shutdown();
            executor.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 3: RATE LIMITING TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rate Limited Logging")
    class RateLimitingTests {

        @Test
        @DisplayName("Rate limiter rejects when limit reached")
        void testRateLimiterRejectsOverLimit() {
            // Given: rate limiter with max 3 concurrent
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch blockLatch = new CountDownLatch(1);
            AtomicInteger activeCount = new AtomicInteger(0);

            var rateLimiter = patterns.new RateLimitedLogger(
                3, // max 3 concurrent
                executor,
                event -> {
                    activeCount.incrementAndGet();
                    try {
                        blockLatch.await(); // Hold the permit
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        activeCount.decrementAndGet();
                    }
                }
            );

            // When: submitting more than limit
            int accepted = 0;
            int rejected = 0;

            for (int i = 0; i < 10; i++) {
                boolean result = rateLimiter.tryLogEvent(
                    new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
                if (result) accepted++;
                else rejected++;
            }

            // Then: only 3 accepted, rest rejected
            assertEquals(3, accepted, "Should accept only up to rate limit");
            assertEquals(7, rejected, "Should reject over rate limit");
            assertEquals(0, rateLimiter.getAvailablePermits(), "All permits should be taken");

            // Cleanup
            blockLatch.countDown();
            executor.shutdownNow();
        }

        @Test
        @DisplayName("Permits are released after task completion")
        void testPermitsReleasedAfterCompletion() throws InterruptedException {
            // Given: rate limiter
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch completionLatch = new CountDownLatch(3);

            var rateLimiter = patterns.new RateLimitedLogger(
                3,
                executor,
                event -> {
                    // Quick task
                    completionLatch.countDown();
                }
            );

            // When: submitting tasks that complete quickly
            for (int i = 0; i < 3; i++) {
                rateLimiter.tryLogEvent(
                    new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
            }

            // Wait for completion
            assertTrue(completionLatch.await(2, TimeUnit.SECONDS));
            Thread.sleep(100); // Allow permit release

            // Then: permits should be available again
            assertEquals(3, rateLimiter.getAvailablePermits(),
                "All permits should be released after completion");

            // Cleanup
            executor.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 4: CIRCUIT BREAKER TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Circuit Breaker")
    class CircuitBreakerTests {

        @Test
        @DisplayName("Circuit opens after consecutive failures")
        void testCircuitOpensOnFailures() throws InterruptedException {
            // Given: circuit breaker with threshold of 3
            ExecutorService executor = Executors.newFixedThreadPool(2);
            AtomicInteger failureCount = new AtomicInteger(0);

            var circuitBreaker = patterns.new CircuitBreakerLogger(
                executor,
                event -> {
                    failureCount.incrementAndGet();
                    throw new RuntimeException("Simulated DB failure");
                },
                3,    // failure threshold
                1000  // reset timeout
            );

            // When: submitting events that will fail
            for (int i = 0; i < 5; i++) {
                circuitBreaker.logEvent(
                    new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
                Thread.sleep(50); // Allow async execution
            }

            Thread.sleep(200); // Allow circuit to open

            // Then: circuit should be open
            assertTrue(circuitBreaker.isCircuitOpen(),
                "Circuit should be open after 3+ failures");

            // And: new events should be rejected immediately
            patterns.resetMetrics();
            boolean accepted = circuitBreaker.logEvent(
                new LogEvent("TEST", "rejected", "details", LocalDateTime.now()));

            assertFalse(accepted, "Events should be rejected when circuit is open");
            assertEquals(1, patterns.getTasksRejected(), "Should track rejection");

            // Cleanup
            executor.shutdown();
        }

        @Test
        @DisplayName("Circuit closes after reset timeout")
        void testCircuitClosesAfterTimeout() throws InterruptedException {
            // Given: circuit breaker with short reset timeout
            ExecutorService executor = Executors.newFixedThreadPool(2);
            AtomicInteger callCount = new AtomicInteger(0);

            var circuitBreaker = patterns.new CircuitBreakerLogger(
                executor,
                event -> {
                    int count = callCount.incrementAndGet();
                    if (count <= 3) {
                        throw new RuntimeException("Initial failures");
                    }
                    // After 3 failures, succeed
                },
                3,   // failure threshold
                200  // short reset timeout for test
            );

            // Trigger failures to open circuit
            for (int i = 0; i < 4; i++) {
                circuitBreaker.logEvent(
                    new LogEvent("TEST", "user" + i, "details", LocalDateTime.now()));
                Thread.sleep(50);
            }

            Thread.sleep(100);
            assertTrue(circuitBreaker.isCircuitOpen(), "Circuit should be open");

            // When: waiting for reset timeout
            Thread.sleep(300);

            // Then: circuit should attempt to close (half-open)
            boolean accepted = circuitBreaker.logEvent(
                new LogEvent("TEST", "recovery", "details", LocalDateTime.now()));

            assertTrue(accepted, "Should accept event after reset timeout");

            Thread.sleep(100);
            assertFalse(circuitBreaker.isCircuitOpen(),
                "Circuit should close after successful request");

            // Cleanup
            executor.shutdown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 5: STRESS TEST
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("System handles high event volume with batching")
        void testHighVolumeWithBatching() throws InterruptedException {
            // Given: batching logger
            ExecutorService executor = Executors.newFixedThreadPool(4);
            AtomicInteger totalEventsWritten = new AtomicInteger(0);

            var logger = patterns.new BatchingEventLogger(
                1000,  // large buffer
                50,    // batch size
                100,   // flush every 100ms
                executor,
                batch -> {
                    totalEventsWritten.addAndGet(batch.size());
                    // Simulate fast batch write
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            );

            // When: submitting many events rapidly
            int eventCount = 500;
            long start = System.currentTimeMillis();

            for (int i = 0; i < eventCount; i++) {
                logger.logEvent(new LogEvent(
                    "STRESS_TEST",
                    "user" + (i % 100),
                    "Event " + i,
                    LocalDateTime.now()
                ));
            }

            // Wait for processing
            Thread.sleep(500);
            logger.shutdown();

            long duration = System.currentTimeMillis() - start;

            // Then: most events should be processed
            System.out.printf("Processed %d/%d events in %dms%n",
                totalEventsWritten.get(), eventCount, duration);

            assertTrue(totalEventsWritten.get() >= eventCount * 0.9,
                "Should process at least 90% of events");

            // Cleanup
            executor.shutdown();
        }

        @Test
        @DisplayName("Metrics accurately track under pressure")
        void testMetricsUnderPressure() throws InterruptedException {
            // Given: rate-limited logger with small limit
            ExecutorService executor = Executors.newFixedThreadPool(4);
            patterns.resetMetrics();

            var rateLimiter = patterns.new RateLimitedLogger(
                5, // only 5 concurrent
                executor,
                event -> {
                    try {
                        Thread.sleep(50); // Slow processing
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            );

            // When: flooding with events
            int totalAttempts = 50;
            int accepted = 0;
            int immediatelyRejected = 0;

            for (int i = 0; i < totalAttempts; i++) {
                boolean result = rateLimiter.tryLogEvent(new LogEvent(
                    "METRICS_TEST", "user" + i, "details", LocalDateTime.now()));
                if (result) accepted++;
                else immediatelyRejected++;
            }

            // Wait for completion
            Thread.sleep(1000);

            // Then: metrics should be consistent
            int submitted = patterns.getTasksSubmitted();
            int completed = patterns.getTasksCompleted();
            int rejected = patterns.getTasksRejected();

            System.out.printf("Metrics - Attempts: %d, Accepted: %d, Submitted: %d, Completed: %d, Rejected: %d%n",
                totalAttempts, accepted, submitted, completed, rejected);

            // Submitted = accepted tasks (got a permit)
            assertEquals(accepted, submitted, "Submitted should match accepted count");
            // All submitted should complete (no failures in this test)
            assertEquals(submitted, completed, "All submitted should complete");
            // Rejected = rate-limited (couldn't get permit)
            assertEquals(immediatelyRejected, rejected, "Rejected should match rate-limited count");
            // Total attempts = accepted + rejected
            assertEquals(totalAttempts, accepted + immediatelyRejected, "Attempts should equal accepted + rejected");

            // Cleanup
            executor.shutdown();
        }
    }
}
