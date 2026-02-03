package com.mortitech.completablefuture.level6_advanced;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Fire-and-Forget patterns.
 *
 * TESTING CHALLENGES:
 * Fire-and-forget is inherently hard to test because:
 * 1. No return value to assert on
 * 2. Operations complete asynchronously
 * 3. Need to verify side effects
 *
 * SOLUTIONS USED:
 * 1. CountDownLatch to wait for async completion
 * 2. AtomicReference to capture values from async context
 * 3. Custom error handlers to verify error handling
 * 4. Built-in counters for success/failure tracking
 */
class FireAndForgetPatternsTest {

    private FireAndForgetPatterns patterns;
    private Executor testExecutor;

    @BeforeEach
    void setUp() {
        testExecutor = Executors.newFixedThreadPool(4);
        patterns = new FireAndForgetPatterns(testExecutor);
        patterns.resetCounters();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TESTING SAFE PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Safe Fire-and-Forget with Logging")
    class SafeLoggingTests {

        @Test
        @DisplayName("Successful task increments success counter")
        void testSuccessfulTask() throws InterruptedException {
            // Given: a task that succeeds
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean taskExecuted = new AtomicBoolean(false);

            // When: firing and forgetting
            patterns.safeFireAndForgetWithLogging(
                () -> {
                    taskExecuted.set(true);
                    latch.countDown();
                },
                "test-success"
            );

            // Then: wait and verify
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Task should complete");
            assertTrue(taskExecuted.get(), "Task should have executed");
            assertEquals(1, patterns.getSuccessCount());
            assertEquals(0, patterns.getFailureCount());
        }

        @Test
        @DisplayName("Failed task increments failure counter and calls error handler")
        void testFailedTask() throws InterruptedException {
            // Given: error handler to capture the exception
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> capturedError = new AtomicReference<>();

            patterns.setErrorHandler(ex -> {
                capturedError.set(ex);
                latch.countDown();
            });

            // When: task fails
            patterns.safeFireAndForgetWithLogging(
                () -> {
                    throw new RuntimeException("Intentional test failure");
                },
                "test-failure"
            );

            // Then: error handler is called
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Error handler should be called");
            assertNotNull(capturedError.get());
            assertTrue(capturedError.get().getMessage().contains("Intentional test failure"));
            assertEquals(0, patterns.getSuccessCount());
            assertEquals(1, patterns.getFailureCount());
        }

        @Test
        @DisplayName("Caller is not blocked by fire-and-forget")
        void testNonBlocking() {
            // Given: a slow task
            AtomicBoolean taskStarted = new AtomicBoolean(false);
            long startTime = System.currentTimeMillis();

            // When: firing and forgetting a slow task
            patterns.safeFireAndForgetWithLogging(
                () -> {
                    taskStarted.set(true);
                    try {
                        Thread.sleep(500); // Slow task
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                "slow-task"
            );

            long elapsed = System.currentTimeMillis() - startTime;

            // Then: method returned almost immediately (not waiting 500ms)
            assertTrue(elapsed < 100, "Fire-and-forget should not block. Elapsed: " + elapsed + "ms");
        }
    }

    @Nested
    @DisplayName("Safe Fire-and-Forget with Retry")
    class SafeRetryTests {

        @Test
        @DisplayName("Retry succeeds after initial failures")
        void testRetryEventualSuccess() throws InterruptedException {
            // Given: a task that fails twice then succeeds
            AtomicInteger attemptCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            // When: firing with retry
            patterns.safeFireAndForgetWithRetry(
                () -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("Fail attempt " + attempt);
                    }
                    // Third attempt succeeds
                    latch.countDown();
                },
                "retry-test",
                3  // max retries
            );

            // Then: task eventually succeeds
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should eventually succeed");
            assertEquals(3, attemptCount.get(), "Should have attempted 3 times");
            // Success count should be 1 (final success)
            // Give time for counter to update
            Thread.sleep(100);
            assertEquals(1, patterns.getSuccessCount());
        }

        @Test
        @DisplayName("Retry exhausts all attempts then calls error handler")
        void testRetryExhaustion() throws InterruptedException {
            // Given: a task that always fails
            AtomicInteger attemptCount = new AtomicInteger(0);
            CountDownLatch errorLatch = new CountDownLatch(1);
            AtomicReference<Throwable> capturedError = new AtomicReference<>();

            patterns.setErrorHandler(ex -> {
                capturedError.set(ex);
                errorLatch.countDown();
            });

            // When: firing with retry
            patterns.safeFireAndForgetWithRetry(
                () -> {
                    attemptCount.incrementAndGet();
                    throw new RuntimeException("Always fails");
                },
                "always-fails",
                2  // max retries
            );

            // Then: error handler called after all retries exhausted
            assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Error handler should be called");
            assertEquals(3, attemptCount.get(), "Should attempt initial + 2 retries = 3 total");
            assertNotNull(capturedError.get());
        }
    }

    @Nested
    @DisplayName("Safe Fire-and-Forget with Fallback")
    class SafeFallbackTests {

        @Test
        @DisplayName("Primary task succeeds, fallback not called")
        void testPrimarySuccess() throws InterruptedException {
            // Given
            CountDownLatch primaryLatch = new CountDownLatch(1);
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);

            // When
            patterns.safeFireAndForgetWithFallback(
                () -> {
                    primaryLatch.countDown();
                },
                () -> {
                    fallbackCalled.set(true);
                },
                "primary-success"
            );

            // Then
            assertTrue(primaryLatch.await(2, TimeUnit.SECONDS));
            Thread.sleep(100); // Give time for completion
            assertFalse(fallbackCalled.get(), "Fallback should not be called on success");
            assertEquals(1, patterns.getSuccessCount());
        }

        @Test
        @DisplayName("Primary fails, fallback executes")
        void testFallbackExecution() throws InterruptedException {
            // Given
            AtomicBoolean fallbackExecuted = new AtomicBoolean(false);
            CountDownLatch fallbackLatch = new CountDownLatch(1);

            // When: primary fails
            patterns.safeFireAndForgetWithFallback(
                () -> {
                    throw new RuntimeException("Primary fails");
                },
                () -> {
                    fallbackExecuted.set(true);
                    fallbackLatch.countDown();
                },
                "fallback-test"
            );

            // Then: fallback is executed
            assertTrue(fallbackLatch.await(2, TimeUnit.SECONDS));
            assertTrue(fallbackExecuted.get(), "Fallback should execute when primary fails");
        }

        @Test
        @DisplayName("Both primary and fallback fail")
        void testBothFail() throws InterruptedException {
            // Given
            CountDownLatch errorLatch = new CountDownLatch(1);
            AtomicReference<Throwable> capturedError = new AtomicReference<>();

            patterns.setErrorHandler(ex -> {
                capturedError.set(ex);
                errorLatch.countDown();
            });

            // When: both fail
            patterns.safeFireAndForgetWithFallback(
                () -> {
                    throw new RuntimeException("Primary fails");
                },
                () -> {
                    throw new RuntimeException("Fallback also fails");
                },
                "both-fail"
            );

            // Then: error handler receives fallback error
            assertTrue(errorLatch.await(2, TimeUnit.SECONDS));
            assertNotNull(capturedError.get());
            assertTrue(capturedError.get().getMessage().contains("Fallback also fails"));
            assertEquals(1, patterns.getFailureCount());
        }
    }

    @Nested
    @DisplayName("Optionally Tracked Task Pattern")
    class OptionallyTrackedTests {

        @Test
        @DisplayName("Can be used as fire-and-forget (ignore return)")
        void testAsFireAndForget() throws InterruptedException {
            // Given
            AtomicBoolean executed = new AtomicBoolean(false);

            // When: ignoring the returned future
            patterns.optionallyTrackedTask(
                () -> executed.set(true),
                "ignored"
            );
            // Not calling .join() - true fire-and-forget

            // Then: task still executes
            Thread.sleep(200);
            assertTrue(executed.get());
        }

        @Test
        @DisplayName("Can be tracked if caller wants")
        void testAsTracked() {
            // Given
            AtomicBoolean executed = new AtomicBoolean(false);

            // When: using the returned future
            CompletableFuture<Void> future = patterns.optionallyTrackedTask(
                () -> executed.set(true),
                "tracked"
            );

            // Then: can wait for completion
            future.join();
            assertTrue(executed.get());
        }

        @Test
        @DisplayName("Caller can handle errors if they choose")
        void testErrorHandlingOption() {
            // Given
            AtomicReference<Throwable> callerCapturedError = new AtomicReference<>();

            // When: caller adds their own error handling
            CompletableFuture<Void> future = patterns.optionallyTrackedTask(
                () -> {
                    throw new RuntimeException("Task error");
                },
                "error-handling"
            );

            future.exceptionally(ex -> {
                callerCapturedError.set(ex);
                return null;
            }).join();

            // Then: caller can capture the error
            assertNotNull(callerCapturedError.get());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TESTING REAL-WORLD EXAMPLES
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Real-World Fire-and-Forget Scenarios")
    class RealWorldTests {

        @Test
        @DisplayName("Analytics tracking doesn't block caller")
        void testAnalyticsTracking() {
            // When: tracking multiple events rapidly
            long start = System.currentTimeMillis();

            for (int i = 0; i < 10; i++) {
                patterns.trackAnalyticsEvent("page_view", "user_" + i);
            }

            long elapsed = System.currentTimeMillis() - start;

            // Then: all calls return immediately (not blocking for 50ms each)
            assertTrue(elapsed < 100, "Analytics tracking should be non-blocking");
        }

        @Test
        @DisplayName("Email notification uses retry")
        void testEmailWithRetry() throws InterruptedException {
            // When: sending email notification
            patterns.sendEmailNotification("test@example.com", "Welcome!");

            // Then: waits for async completion
            patterns.awaitCompletion(500);

            // Email should have been "sent" (logged)
            assertEquals(1, patterns.getSuccessCount());
        }

        @Test
        @DisplayName("Audit log with fallback")
        void testAuditWithFallback() throws InterruptedException {
            // When: writing audit log
            patterns.writeAuditLog("USER_LOGIN", "userId=123");

            // Then: should complete successfully
            patterns.awaitCompletion(200);
            assertEquals(1, patterns.getSuccessCount());
        }

        @Test
        @DisplayName("Cache warming doesn't block")
        void testCacheWarming() {
            // When: warming cache (slow operation)
            long start = System.currentTimeMillis();
            patterns.warmCache("user-preferences");
            long elapsed = System.currentTimeMillis() - start;

            // Then: returns immediately (cache warming takes 200ms)
            assertTrue(elapsed < 50, "Cache warming should not block caller");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DEMONSTRATING THE DANGER OF UNSAFE PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Demonstrating Unsafe Pattern Dangers")
    class UnsafePatternDangers {

        @Test
        @DisplayName("Dangerous pattern loses exceptions completely")
        void testDangerousPatternLosesExceptions() throws InterruptedException {
            // Given: a task that throws
            AtomicBoolean errorDetected = new AtomicBoolean(false);

            // When: using dangerous fire-and-forget
            patterns.dangerousFireAndForget_BAD(() -> {
                throw new RuntimeException("This exception is LOST!");
            });

            // Wait for task to "complete" (fail)
            Thread.sleep(200);

            // Then: no way to know it failed!
            // - No counter incremented
            // - No error handler called
            // - Exception completely swallowed
            assertEquals(0, patterns.getSuccessCount(), "Success not tracked");
            assertEquals(0, patterns.getFailureCount(), "Failure not tracked either!");

            // This is why dangerous pattern is... dangerous
            assertFalse(errorDetected.get(), "We have no way to detect the error");
        }

        @Test
        @DisplayName("Safe pattern captures errors that dangerous pattern loses")
        void testSafeVsDangerous() throws InterruptedException {
            // Setup error tracking
            CountDownLatch errorLatch = new CountDownLatch(1);
            AtomicReference<Throwable> capturedError = new AtomicReference<>();

            patterns.setErrorHandler(ex -> {
                capturedError.set(ex);
                errorLatch.countDown();
            });

            // When: using SAFE pattern with same failing task
            patterns.safeFireAndForgetWithLogging(
                () -> {
                    throw new RuntimeException("This exception is CAPTURED!");
                },
                "safe-test"
            );

            // Then: error IS captured
            assertTrue(errorLatch.await(2, TimeUnit.SECONDS), "Error should be captured");
            assertNotNull(capturedError.get());
            assertTrue(capturedError.get().getMessage().contains("This exception is CAPTURED!"),
                "Should contain original message");
            assertEquals(1, patterns.getFailureCount(), "Failure IS tracked");
        }
    }
}
