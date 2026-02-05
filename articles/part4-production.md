# Mastering Java CompletableFuture — Part 4: Production Patterns & Testing

*Fire-and-forget done right, surviving high-pressure scenarios, and writing async tests that don't flake.*

---

## The Dark Side of Async

We've learned how to compose async operations, handle errors, and integrate with Spring Boot. Now let's tackle the patterns that separate production code from tutorial code.

Three challenges await:
1. **Fire-and-forget:** When you don't wait for results—and why that's dangerous
2. **High pressure:** What happens when tasks arrive faster than you can process them
3. **Testing:** Writing async tests that are fast and deterministic

Let's dive in.

---

## Fire-and-Forget: Handle With Care

Fire-and-forget is when you start an async operation but don't wait for it to complete:

```java
// Fire-and-forget: Start the task and move on
CompletableFuture.runAsync(() -> sendAnalyticsEvent(event));
// Execution continues immediately. Who knows what happens to that task?
```

### When fire-and-forget is appropriate

- Non-critical operations (analytics, metrics)
- Notifications where occasional failure is acceptable
- Cache warming/pre-loading
- Audit trails that shouldn't block the main flow

### When fire-and-forget is dangerous

- Critical business operations
- Operations where failure must be reported
- When you need to track completion
- When backpressure is needed (more on this later)

---

## The Danger: Silent Failures

This is the most common fire-and-forget pattern, and it's **terrible**:

```java
// ❌ DANGEROUS: Exception vanishes into the void
public void logAnalytics_BAD(String event) {
    CompletableFuture.runAsync(() -> {
        analyticsService.send(event);  // If this throws... 🤷‍♂️
    });
}
```

If `analyticsService.send()` throws an exception:
- No stack trace printed
- No error logged
- No way to know it failed
- Your analytics silently stops working

This looks like it handles errors, but it doesn't:

```java
// ❌ ALSO BAD: Catches error but does nothing useful
CompletableFuture.runAsync(() -> analyticsService.send(event))
    .exceptionally(ex -> {
        return null;  // Error caught... and thrown away
    });
```

---

## Safe Fire-and-Forget Patterns

### Pattern 1: Always log errors

At minimum, **always log errors** in fire-and-forget operations:

```java
public void safeFireAndForget(Runnable task, String taskName) {
    CompletableFuture.runAsync(task, executor)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[FIRE-AND-FORGET] Task '{}' failed: {}",
                    taskName, ex.getMessage(), ex);
                metrics.increment("async." + taskName + ".error");
            } else {
                metrics.increment("async." + taskName + ".success");
            }
        });
}
```

Now at least you'll know when things break.

### Pattern 2: Fire-and-forget with retry

For important operations that shouldn't block but need reliability:

```java
public void fireAndForgetWithRetry(Runnable task, String name, int maxRetries) {
    executeWithRetry(task, name, maxRetries, 0);
}

private void executeWithRetry(Runnable task, String name, int maxRetries, int attempt) {
    CompletableFuture.runAsync(task, executor)
        .whenComplete((result, ex) -> {
            if (ex != null && attempt < maxRetries) {
                log.warn("Task '{}' failed, retrying ({}/{})",
                    name, attempt + 1, maxRetries);

                // Simple exponential backoff
                sleep(100 * (attempt + 1));
                executeWithRetry(task, name, maxRetries, attempt + 1);
            } else if (ex != null) {
                log.error("Task '{}' failed after {} retries", name, maxRetries, ex);
                metrics.increment("async." + name + ".final_failure");
            }
        });
}
```

### Pattern 3: Fire-and-forget with fallback

When you have a backup plan for failures:

```java
public void fireAndForgetWithFallback(
        Runnable primaryTask,
        Runnable fallbackTask,
        String taskName) {

    CompletableFuture.runAsync(primaryTask, executor)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Primary task '{}' failed, executing fallback", taskName);
                try {
                    fallbackTask.run();
                } catch (Exception fallbackEx) {
                    log.error("Both primary and fallback failed for '{}'",
                        taskName, fallbackEx);
                }
            }
        });
}

// Usage: try to log to database, fall back to local file
fireAndForgetWithFallback(
    () -> auditDatabase.log(event),
    () -> auditFile.log(event),
    "audit-log"
);
```

### Pattern 4: Return the future (let caller decide)

The **best pattern when possible**—return the future so callers can track it if they want:

```java
public CompletableFuture<Void> optionallyTrackedTask(Runnable task, String name) {
    return CompletableFuture.runAsync(task, executor)
        .whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Task '{}' failed", name, ex);
            }
        });
}

// Caller can:
// 1. Ignore return value (fire-and-forget)
optionallyTrackedTask(() -> sendEmail(), "email");

// 2. Chain more work
optionallyTrackedTask(() -> sendEmail(), "email")
    .thenRun(() -> log.info("Email sent"));

// 3. Wait for completion
optionallyTrackedTask(() -> sendEmail(), "email").join();
```

This gives flexibility without forcing a choice.

---

## High Pressure: When Tasks Overwhelm Your System

Now for the scenario that crashes production systems: **high-pressure fire-and-forget.**

Picture this:
- You're logging events to a database
- Normal load: 100 events/second (database handles fine)
- Spike: 10,000 events/second
- Each event spawns a fire-and-forget task

What happens?

### The disaster unfolds

1. **Thread pool saturates:** All threads are busy
2. **Queue fills up:** Tasks pile up waiting for threads
3. **Memory exhausted:** Unbounded queue grows until OOM
4. **System crashes:** Or becomes unresponsive

```java
// ❌ RECIPE FOR DISASTER
// Executors.newFixedThreadPool uses an unbounded LinkedBlockingQueue
ExecutorService executor = Executors.newFixedThreadPool(4);

// Under high load, queue grows without limit
for (Event event : highVolumeStream) {
    CompletableFuture.runAsync(() -> database.write(event), executor);
    // Queue: 1, 10, 100, 1000, 10000, 100000... 💥
}
```

---

## Solution 1: Bounded Queues with Rejection Policies

First, **bound your queue**. When it's full, a rejection policy decides what happens:

```java
public ExecutorService createSafeExecutor(int threads, int queueSize) {
    return new ThreadPoolExecutor(
        threads, threads,
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueSize),  // BOUNDED queue!
        new ThreadPoolExecutor.CallerRunsPolicy()  // Rejection policy
    );
}
```

### Rejection policies explained

| Policy | What it does | Use when |
|--------|--------------|----------|
| `AbortPolicy` | Throws `RejectedExecutionException` | You want to know immediately when overwhelmed |
| `CallerRunsPolicy` | Caller thread runs the task | Backpressure is OK; slowing producers is acceptable |
| `DiscardPolicy` | Silently drops the task | Losing some tasks is acceptable (sampling) |
| `DiscardOldestPolicy` | Drops oldest queued task | Newer data is more important |

**My recommendation:** `CallerRunsPolicy` for fire-and-forget logging. It creates natural backpressure—when you're overwhelmed, the producer slows down.

### Custom rejection with monitoring

```java
ExecutorService executor = new ThreadPoolExecutor(
    4, 4,
    0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(1000),
    (runnable, pool) -> {
        rejectedTasksCounter.increment();
        log.warn("Task rejected - queue full. Total rejected: {}",
            rejectedTasksCounter.get());

        // Option: run on caller thread (backpressure)
        if (!pool.isShutdown()) {
            runnable.run();
        }
    }
);
```

Now you can monitor and alert on rejections.

---

## Solution 2: Batching for Database Writes

Writing one row per event is inefficient under load. **Batch your writes.**

```java
public class BatchingEventLogger {
    private final BlockingQueue<LogEvent> buffer;
    private final int batchSize;
    private final long maxDelayMs;
    private final ScheduledExecutorService scheduler;

    public BatchingEventLogger(int bufferSize, int batchSize, long maxDelayMs) {
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
        this.batchSize = batchSize;
        this.maxDelayMs = maxDelayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // Flush periodically even if batch not full
        scheduler.scheduleAtFixedRate(
            this::flush, maxDelayMs, maxDelayMs, TimeUnit.MILLISECONDS
        );
    }

    public boolean logEvent(LogEvent event) {
        boolean added = buffer.offer(event);

        if (!added) {
            log.warn("Event buffer full - dropping event");
            return false;
        }

        // Flush immediately if batch is ready
        if (buffer.size() >= batchSize) {
            flush();
        }

        return true;
    }

    private void flush() {
        List<LogEvent> batch = new ArrayList<>(batchSize);
        buffer.drainTo(batch, batchSize);

        if (!batch.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                database.batchInsert(batch);  // One write for 100 events
                log.debug("Flushed {} events to database", batch.size());
            }, executor);
        }
    }
}
```

Benefits:
- 100 events = 1 database write instead of 100
- Buffer provides natural backpressure
- Periodic flush ensures events don't wait too long

---

## Solution 3: Rate Limiting with Semaphore

Control concurrent operations regardless of queue size:

```java
public class RateLimitedLogger {
    private final Semaphore permits;
    private final ExecutorService executor;

    public RateLimitedLogger(int maxConcurrent, ExecutorService executor) {
        this.permits = new Semaphore(maxConcurrent);
        this.executor = executor;
    }

    public boolean tryLog(LogEvent event) {
        if (!permits.tryAcquire()) {
            return false;  // At capacity - reject immediately
        }

        CompletableFuture.runAsync(() -> {
            try {
                database.write(event);
            } finally {
                permits.release();  // Always release!
            }
        }, executor);

        return true;
    }

    // With wait timeout
    public boolean logWithWait(LogEvent event, long timeoutMs)
            throws InterruptedException {
        if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            return false;
        }
        // ... same as above
    }
}
```

This guarantees at most N concurrent database operations, regardless of how fast events arrive.

---

## Solution 4: Circuit Breaker

When the database is failing, stop hammering it:

```java
public class CircuitBreakerLogger {
    private volatile boolean circuitOpen = false;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int failureThreshold;
    private volatile long circuitOpenedAt = 0;
    private final long resetTimeoutMs;

    public boolean logEvent(LogEvent event) {
        // Check if circuit is open
        if (circuitOpen) {
            if (System.currentTimeMillis() - circuitOpenedAt > resetTimeoutMs) {
                // Half-open: try one request
                circuitOpen = false;
                log.info("Circuit breaker attempting to close...");
            } else {
                log.debug("Circuit open - rejecting event");
                return false;
            }
        }

        CompletableFuture.runAsync(() -> {
            try {
                database.write(event);
                consecutiveFailures.set(0);  // Reset on success
            } catch (Exception e) {
                int failures = consecutiveFailures.incrementAndGet();

                if (failures >= failureThreshold) {
                    circuitOpen = true;
                    circuitOpenedAt = System.currentTimeMillis();
                    log.error("Circuit breaker OPENED after {} failures", failures);
                }
            }
        }, executor);

        return true;
    }
}
```

When the database fails 5 times in a row:
1. Circuit opens (stops sending requests)
2. Waits for reset timeout
3. Tries one request (half-open state)
4. If it succeeds, closes circuit; if it fails, stays open

This prevents cascading failures and gives the database time to recover.

---

## Testing Async Code

Async code is notoriously hard to test. Race conditions, timing issues, flaky tests. Here's how to do it right.

### Strategy 1: Use a Synchronous Executor

For unit tests, use an executor that runs tasks immediately on the calling thread:

```java
// This magical one-liner eliminates most async testing pain
Executor synchronousExecutor = Runnable::run;
```

Now your async code becomes synchronous in tests:

```java
@BeforeEach
void setUp() {
    service = new UserService(
        mockRepository,
        mockClient,
        Runnable::run  // Synchronous executor
    );
}

@Test
void testAsyncOperation() {
    when(mockRepository.findById(1L)).thenReturn(expectedUser);

    // This is deterministic now - no race conditions
    User result = service.findUserAsync(1L).join();

    assertEquals("expected_user", result.username());
    verify(mockRepository).findById(1L);
}
```

### Strategy 2: Use completedFuture() and failedFuture()

For mocking methods that return `CompletableFuture`:

```java
// Mock a successful async response
when(asyncClient.fetchData(anyLong()))
    .thenReturn(CompletableFuture.completedFuture(expectedData));

// Mock a failed async response
when(asyncClient.fetchData(anyLong()))
    .thenReturn(CompletableFuture.failedFuture(
        new ServiceException("Connection failed")
    ));
```

These create pre-completed futures—no actual async behavior to worry about.

### Strategy 3: Test Error Handling Paths

Don't just test the happy path:

```java
@Test
void testFallbackOnError() {
    // Given: repository throws
    when(userRepository.findById(anyLong()))
        .thenThrow(new RuntimeException("DB Error"));

    // When: calling method with fallback
    User result = service.findUserWithFallback(1L).join();

    // Then: fallback user returned (not exception)
    assertEquals("unknown", result.username());
}

@Test
void testExceptionPropagatesWithoutFallback() {
    when(userRepository.findById(anyLong()))
        .thenThrow(new RuntimeException("DB Error"));

    CompletableFuture<User> future = service.findUserById(1L);

    // Exception wrapped in CompletionException
    CompletionException thrown = assertThrows(
        CompletionException.class,
        future::join
    );
    assertEquals("DB Error", thrown.getCause().getMessage());
}
```

### Strategy 4: Test Timeouts (With Real Async)

For timeout testing, you need actual async behavior:

```java
@Test
void testTimeoutThrowsException() {
    // Use real async executor for this test
    Executor asyncExecutor = Executors.newSingleThreadExecutor();
    UserService asyncService = new UserService(mockRepo, asyncExecutor);

    // Mock a slow operation
    when(mockRepo.findById(anyLong())).thenAnswer(inv -> {
        Thread.sleep(500);  // Slower than timeout
        return expectedUser;
    });

    // Call with short timeout
    CompletableFuture<User> future = asyncService
        .findUserWithTimeout(1L, 50);  // 50ms timeout

    // Should throw TimeoutException
    CompletionException thrown = assertThrows(
        CompletionException.class,
        future::join
    );
    assertInstanceOf(TimeoutException.class, thrown.getCause());
}
```

### Strategy 5: Verify Parallel Execution

Ensure operations actually run in parallel:

```java
@Test
void testOperationsRunInParallel() {
    Executor parallelExecutor = Executors.newFixedThreadPool(3);
    UserService service = new UserService(mocks..., parallelExecutor);

    // Each operation takes 100ms
    when(userRepo.findById(1L)).thenAnswer(inv -> {
        Thread.sleep(100);
        return user;
    });
    when(prefsClient.get(1L)).thenAnswer(inv -> {
        Thread.sleep(100);
        return prefs;
    });
    when(loyaltyClient.get(1L)).thenAnswer(inv -> {
        Thread.sleep(100);
        return points;
    });

    // Fetch profile (runs all three in parallel)
    long start = System.currentTimeMillis();
    UserProfile profile = service.getUserProfile(1L).join();
    long duration = System.currentTimeMillis() - start;

    // Should complete in ~100ms (parallel), not ~300ms (sequential)
    assertTrue(duration < 200,
        "Expected parallel execution (~100ms) but took " + duration + "ms");
}
```

### Strategy 6: Test Pure Functions Separately

Extract business logic into pure functions and test them without any async:

```java
// In your service
UserProfile buildProfile(User user, List<String> prefs, int points) {
    String plan = calculatePlan(user.tier(), points);
    return UserProfile.of(user, prefs, LocalDateTime.now(), points, plan);
}

String calculatePlan(String tier, int points) {
    if (points > 5000) return "VIP";
    if (points > 2000 && "BASIC".equals(tier)) return "PREMIUM";
    return tier;
}

// In tests - fast, deterministic, no mocking needed
@Test
void testPlanCalculation() {
    assertEquals("VIP", service.calculatePlan("BASIC", 6000));
    assertEquals("PREMIUM", service.calculatePlan("BASIC", 3000));
    assertEquals("BASIC", service.calculatePlan("BASIC", 500));
}
```

---

## Testing Checklist

For async code, ensure you test:

- [ ] Success path with synchronous executor
- [ ] Error handling (exceptions, fallbacks)
- [ ] Timeout behavior
- [ ] Partial failures in parallel operations
- [ ] Future state inspection (`isDone()`, `isCompletedExceptionally()`)
- [ ] Parallel execution timing (when it matters)
- [ ] Pure business logic separately

---

## Production Checklist

Before deploying async code:

- [ ] **Executors:** Custom executors with meaningful thread names
- [ ] **Bounded queues:** Never use unbounded queues in production
- [ ] **Rejection policy:** Know what happens when overwhelmed
- [ ] **Error handling:** Every fire-and-forget logs errors at minimum
- [ ] **Monitoring:** Track submitted, completed, rejected, failed counts
- [ ] **Graceful shutdown:** `executor.setWaitForTasksToCompleteOnShutdown(true)`
- [ ] **Backpressure:** Have a strategy for when load exceeds capacity
- [ ] **Circuit breaker:** Protect against cascading failures

---

## Key Takeaways

1. **Fire-and-forget is dangerous.** Always log errors, always track metrics. Return the future when possible.

2. **Bound your queues.** Unbounded queues lead to memory exhaustion. Use `ArrayBlockingQueue` with rejection policies.

3. **`CallerRunsPolicy` is your friend.** It creates natural backpressure—when overwhelmed, slow down the producer.

4. **Batch for efficiency.** 100 events in 1 write beats 100 separate writes.

5. **Use synchronous executors in tests.** `Runnable::run` makes async code deterministic.

6. **Test error paths.** Happy paths work in demos. Error paths work in production.

7. **Extract pure functions.** Business logic without async mechanics is easy to test.

---

## Wrapping Up the Series

We've covered the complete journey:

- **Part 1:** The mental model—async as asking for a callback
- **Part 2:** Composition—`thenApply` vs `thenCompose`, parallel execution
- **Part 3:** Error handling and Spring Boot integration
- **Part 4:** Production patterns—fire-and-forget, high pressure, testing

`CompletableFuture` is a powerful tool. Used well, it makes your applications faster and more responsive. Used poorly, it creates silent failures and mysterious bugs.

The difference is in the details: proper error handling, bounded queues, meaningful thread names, comprehensive tests.

Now go make your async code production-ready.

---

*[← Part 3: Spring Boot Integration](./part3-spring-boot.md) | [Back to Part 1 →](./part1-foundation.md)*
