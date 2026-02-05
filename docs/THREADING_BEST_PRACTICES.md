# Threading Best Practices for CompletableFuture

This document explains the threading challenges when combining synchronous and asynchronous operations, and provides best practices for handling high-load scenarios.

## The Problem: Payment + Audit Scenario

A common real-world pattern:

```java
public void processPayment(String userId, BigDecimal amount) {
    // DB read (10-20ms)
    User user = userRepository.findById(userId);

    // Business logic (fast)
    Payment payment = createPayment(user, amount);

    // DB write (10-20ms)
    paymentRepository.save(payment);

    // Fire-and-forget audit (50-100ms DB write)
    logAuditAsync(createAuditLog(payment));  // Uses CompletableFuture
}

public CompletableFuture<Void> logAuditAsync(AuditLog log) {
    return CompletableFuture.runAsync(() -> {
        auditRepository.save(log);  // Slow DB operation
    });  // Which executor does this use?
}
```

### The Thread Usage Question

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  1000 concurrent payment requests arrive                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Request Threads (e.g., Tomcat: 200 threads)                                │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                                                                       │  │
│  │ Request 1 → processPayment()                                          │  │
│  │               ├── findById()  [DB read: 20ms]                         │  │
│  │               ├── save()      [DB write: 20ms]                        │  │
│  │               └── logAuditAsync() ───────────────────────┐            │  │
│  │                                                          │            │  │
│  │ Request 2 → processPayment()                             │            │  │
│  │               ├── findById()  [DB read: 20ms]            │            │  │
│  │               ├── save()      [DB write: 20ms]           │            │  │
│  │               └── logAuditAsync() ───────────────────────┤            │  │
│  │ ...                                                      │            │  │
│  │                                                          │            │  │
│  │ ⚠️ Each thread BLOCKED ~40ms on DB I/O                   │            │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                       │                                     │               │
│                       ▼                                     │               │
│  Database Connection Pool (e.g., HikariCP: 10-50 connections)               │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 🔌 Only 10-50 connections available                                   │  │
│  │ ⏳ Threads waiting for connections → potential timeouts               │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                             │               │
│                                                             ▼               │
│  CompletableFuture Executor (ForkJoinPool.commonPool by default)            │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 🧵 Thread 1: auditRepository.save() [50ms]                            │  │
│  │ 🧵 Thread 2: auditRepository.save() [50ms]                            │  │
│  │ ...                                                                   │  │
│  │ 🧵 Thread 7: auditRepository.save() [50ms] ← Only 7 on 8-core CPU!    │  │
│  │                                                                       │  │
│  │ 📥 Queue: audits piling up faster than processed...                   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ❓ Three bottlenecks: Tomcat threads, DB connections, and commonPool       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Understanding Default Executor Behavior

### ForkJoinPool.commonPool() (Default)

When you call `CompletableFuture.runAsync()` without specifying an executor:

```java
CompletableFuture.runAsync(() -> {
    // This runs on ForkJoinPool.commonPool()
});
```

**Default pool size:** `Runtime.getRuntime().availableProcessors() - 1`

| CPU Cores | commonPool Threads | Max Concurrent Audits |
|-----------|-------------------|----------------------|
| 4 cores   | 3 threads         | 3 audits at a time   |
| 8 cores   | 7 threads         | 7 audits at a time   |
| 16 cores  | 15 threads        | 15 audits at a time  |

**The Math Problem:**

| Resource | Capacity | Throughput Limit |
|----------|----------|------------------|
| DB connection pool (10 conn) | 10 × (1000ms / 40ms) | ~250 payments/sec |
| Tomcat threads (200) | Limited by DB pool | ~250 payments/sec |
| commonPool (7 threads) | 7 × (1000ms / 50ms) | ~140 audits/sec |

- Payments complete at ~250/sec (DB pool is the limit)
- Audits process at ~140/sec (commonPool is the limit)
- **110 audits/second pile up in the queue**
- Result: Memory exhaustion or massive latency

---

## The Threading Problems

### Problem 1: Shared Executor Starvation

When payments and audits share the same executor (or when using `CallerRunsPolicy`):

```java
// BAD: Audits use the same pool, CallerRunsPolicy blocks callers
ExecutorService sharedPool = new ThreadPoolExecutor(
    10, 10, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(20),
    new ThreadPoolExecutor.CallerRunsPolicy()  // Backpressure blocks caller!
);

public void processPayment(...) {
    // Payment runs on request thread (fast: 5ms)
    Payment payment = createPayment(userId, amount);

    // Audit submitted to pool - but if queue is full,
    // CallerRunsPolicy runs it on THIS thread!
    logAuditAsync(createAuditLog(payment));
}

public CompletableFuture<Void> logAuditAsync(...) {
    return CompletableFuture.runAsync(() -> {
        auditRepository.save(log);  // Slow: 50-100ms
    }, sharedPool);
}
```

**What happens under load:**
1. Pool threads are busy with slow audits
2. Queue fills up (20 tasks)
3. `CallerRunsPolicy` kicks in → audit runs on **request thread**
4. Request thread blocked for 50-100ms instead of 5ms
5. **Result: Payment latency spikes from 5ms to 50-100ms**

### Problem 2: ForkJoinPool.commonPool() is Shared Globally

```java
// Your code
CompletableFuture.runAsync(() -> auditRepository.save(log));

// Some library's code
CompletableFuture.runAsync(() -> httpClient.fetch(url));

// Another library
CompletableFuture.runAsync(() -> cacheClient.get(key));

// ALL USE THE SAME 7 THREADS!
```

If any of these operations block, they affect ALL async operations in your entire application.

### Problem 3: Unbounded Queues Lead to OOM

```java
// DANGEROUS: Default LinkedBlockingQueue is unbounded
ExecutorService pool = Executors.newFixedThreadPool(4);

// Under load: queue grows indefinitely → OutOfMemoryError
for (int i = 0; i < 1_000_000; i++) {
    pool.submit(() -> slowOperation());  // Queue keeps growing!
}
```

---

## Solutions

### Solution 1: Separate Executors ✅ (Recommended)

Isolate concerns with dedicated executors:

```java
@Configuration
public class ExecutorConfig {

    @Bean("paymentExecutor")
    public ExecutorService paymentExecutor() {
        return new ThreadPoolExecutor(
            10, 20,                          // Core/max threads
            60L, TimeUnit.SECONDS,           // Keep-alive
            new ArrayBlockingQueue<>(100),   // BOUNDED queue
            new ThreadPoolExecutor.CallerRunsPolicy()  // Backpressure
        );
    }

    @Bean("auditExecutor")
    public ExecutorService auditExecutor() {
        return new ThreadPoolExecutor(
            5, 10,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),  // Larger queue for audits
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

```java
@Service
public class PaymentService {

    @Qualifier("auditExecutor")
    private final ExecutorService auditExecutor;

    public void processPayment(String userId, BigDecimal amount) {
        // Payment runs on request thread (Tomcat)
        Payment payment = createPayment(userId, amount);

        // Audit runs on SEPARATE executor
        logAuditAsync(createAuditLog(payment));
    }

    public CompletableFuture<Void> logAuditAsync(AuditLog log) {
        return CompletableFuture.runAsync(
            () -> auditRepository.save(log),
            auditExecutor  // Dedicated executor!
        );
    }
}
```

**Benefits:**
- ✅ Audit slowdowns don't affect payment processing
- ✅ Can tune each executor independently
- ✅ Clear separation of concerns
- ✅ Audit failures don't cascade to payments

### Solution 2: Virtual Threads (Java 21+) ✅ (Best for I/O)

```java
@Bean("auditExecutor")
public ExecutorService auditExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

**How Virtual Threads Work:**

```
Traditional Threads (Platform Threads):
┌────────────────────────────────────────────────┐
│ 1 Platform Thread = 1 OS Thread = ~1MB stack   │
│ 10 threads = 10MB memory                       │
│ 1000 threads = 1GB memory (and OS limits)      │
└────────────────────────────────────────────────┘

Virtual Threads (Java 21+):
┌────────────────────────────────────────────────┐
│ 1 Virtual Thread = starts small, grows as needed│
│ Millions of virtual threads possible           │
│ Automatically yield during blocking I/O*       │
│ Platform threads are reused efficiently        │
└────────────────────────────────────────────────┘
*Except when "pinned" (see caveats below)
```

**Benefits:**
- ✅ Scales to millions of concurrent operations
- ✅ No thread pool sizing decisions needed
- ✅ Blocking I/O doesn't waste platform threads
- ✅ Perfect for I/O-bound operations (DB, HTTP, etc.)

**When to Use:**
- ✅ I/O-bound operations (database, network)
- ✅ High concurrency requirements
- ✅ Java 21+

**When NOT to Use:**
- ❌ CPU-intensive operations (use platform threads)
- ❌ Code with heavy `synchronized` blocks (causes "pinning" to carrier thread)
- ❌ Native code/JNI calls (also causes pinning)
- ❌ Pre-Java 21 environments

**Caveat - Thread Pinning:**
Virtual threads "pin" to their carrier thread (blocking it) when:
- Inside a `synchronized` block or method
- Executing native code via JNI

Pinned virtual threads lose their scalability benefit. Prefer `ReentrantLock` over `synchronized` when using virtual threads.

**Note on ThreadLocal:**
Virtual threads do NOT share ThreadLocals—each has its own values. However, ThreadLocal can be expensive at massive scale (millions of threads), so consider alternatives like scoped values (preview in Java 21+).

### Solution 3: Same-Thread Execution (When You Must Wait)

**Common misconception:** "If the operation is fast, don't use async."

**Reality:** The decision depends on **whether you need to wait**, not how fast the operation is.

#### Benchmark Data: Fast Audit (5ms)

> **Note:** These are example results from a specific environment. Actual numbers vary by hardware, JVM, OS, and system load. Run `FastAuditOverheadTest` to measure on your system.

| Metric | Sync | Async (fire-and-forget) |
|--------|------|-------------------------|
| **Response latency** | ~10-12 ms | ~5-6 ms |
| **Improvement** | baseline | **~50% faster** |
| **Async overhead** | - | ~0.1-1 ms |

**Key finding:** Even for fast operations (5ms), async fire-and-forget typically improves response time significantly because the caller doesn't wait for the audit to complete.

#### When to Use Sync vs Async for Fast Operations

| Scenario | Recommendation | Why |
|----------|----------------|-----|
| Audit MUST complete before response | **SYNC** | Simpler, avoids async+join overhead |
| Audit is best-effort (fire-and-forget) | **ASYNC** | Caller returns immediately, ~50% faster response |
| Operation < 1ms AND must wait | **SYNC** | Thread switch overhead may exceed benefit |
| High traffic, need low latency | **ASYNC** | Response time matters more than total work |

#### Code Example: When Audit Must Complete (Use Sync)

```java
// INSTEAD OF:
public CompletableFuture<Void> logAuditAsync(AuditLog log) {
    return CompletableFuture.runAsync(() -> {
        fastAuditOperation(log);  // 2ms operation
    }, executor);  // Thread switch overhead: ~1-5ms
}

// USE:
public void processPayment(...) {
    Payment payment = createPayment(userId, amount);

    // If audit is fast, just do it synchronously
    fastAuditOperation(createAuditLog(payment));  // No thread overhead
}
```

**Or use `thenRun()` instead of `thenRunAsync()`:**

```java
CompletableFuture.runAsync(() -> {
    processPayment();
}, executor)
.thenRun(() -> {
    // Avoids guaranteed thread switch - may run on same thread
    fastAuditOperation();
})
.thenRunAsync(() -> {
    // Runs on DIFFERENT thread from pool
    slowOperation();
}, executor);
```

| Method | Thread Behavior |
|--------|----------------|
| `thenRun()` | **No guaranteed switch** - runs on completing thread OR caller thread |
| `thenRunAsync()` | Different thread from common pool |
| `thenRunAsync(r, executor)` | Different thread from specified executor |

> ⚠️ **Important:** `thenRun()` thread behavior is non-deterministic:
> - If the future is **already complete** when `thenRun()` is called → runs on the **caller's** thread
> - If the future is **not yet complete** → runs on the thread that **completes** the future
>
> Use `thenRun()` to **avoid guaranteed thread switch overhead**, not when you **require** same-thread execution.

### Solution 4: Bounded Queues with Rejection Policies ✅

Always use bounded queues in production:

```java
ExecutorService executor = new ThreadPoolExecutor(
    4, 8,                           // Core/max threads
    60L, TimeUnit.SECONDS,          // Keep-alive
    new ArrayBlockingQueue<>(100),  // BOUNDED queue (100 max)
    rejectionPolicy                 // What to do when full
);
```

**Rejection Policies:**

| Policy | Behavior | Use When |
|--------|----------|----------|
| `AbortPolicy` | Throws `RejectedExecutionException` | You need to know immediately |
| `CallerRunsPolicy` | Caller thread executes task | Backpressure is acceptable |
| `DiscardPolicy` | Silently drops task | Losing tasks is OK (metrics) |
| `DiscardOldestPolicy` | Drops oldest, retries new | Newer data is more important |

**Recommended: Custom Policy with Metrics**

```java
RejectedExecutionHandler monitoredPolicy = (runnable, executor) -> {
    metrics.increment("executor.rejected");
    log.warn("Task rejected - queue full. Consider scaling.");

    // Fallback: run on caller thread (backpressure)
    if (!executor.isShutdown()) {
        runnable.run();
    }
};
```

---

## Decision Matrix

| Scenario | Recommended Solution |
|----------|---------------------|
| High-volume async operations | Separate executors with bounded queues |
| I/O-bound operations (Java 21+) | Virtual threads |
| Fast operations (< 5ms) that must complete | Synchronous execution (no async) |
| Fast continuations after async work | `thenRun()` (avoids thread switch overhead) |
| CPU-intensive operations | Dedicated fixed-size pool |
| Mixed workloads | Separate pools per workload type |

---

## Thread Pool Sizing Guidelines

### For I/O-Bound Operations (DB, HTTP)

```
Threads = CPU Cores × (1 + Wait Time / Service Time)

Example: 8 cores, 50ms DB wait, 5ms processing
Threads = 8 × (1 + 50/5) = 8 × 11 = 88 threads
```

### For CPU-Bound Operations

```
Threads = CPU Cores (or CPU Cores + 1)
```

### For Virtual Threads

```
No sizing needed - scales automatically
Use: Executors.newVirtualThreadPerTaskExecutor()
```

---

## Quick Reference

### DO ✅

```java
// Separate executors for different concerns
@Bean("auditExecutor") ExecutorService auditExecutor() { ... }
@Bean("notificationExecutor") ExecutorService notificationExecutor() { ... }

// Bounded queues
new ArrayBlockingQueue<>(100)

// Virtual threads for I/O (Java 21+)
Executors.newVirtualThreadPerTaskExecutor()

// Explicit executor in CompletableFuture
CompletableFuture.runAsync(task, auditExecutor)

// thenRun() for fast continuations
future.thenRun(() -> fastOperation())
```

### DON'T ❌

```java
// Unbounded queue (OOM risk)
new LinkedBlockingQueue<>()

// Relying on commonPool() for I/O
CompletableFuture.runAsync(() -> dbOperation())  // No executor specified

// Shared executor for different concerns
sharedPool.submit(payment);
sharedPool.submit(audit);
sharedPool.submit(notification);

// thenRunAsync() for fast continuations (adds thread switch overhead)
future.thenRunAsync(() -> fastOperation())  // Use thenRun() instead
```

---

## Test Coverage

See [PaymentAuditThreadingTest.java](../src/test/java/com/mortitech/completablefuture/level6_advanced/PaymentAuditThreadingTest.java) for comprehensive tests covering:

1. **Shared Executor Problem** - demonstrates starvation
2. **Separate Executors Solution** - isolation prevents starvation
3. **Virtual Threads Solution** - scales to thousands without exhaustion
4. **Same-Thread Execution** - for fast operations
5. **Comprehensive Comparison** - all strategies under load

See [FastAuditOverheadTest.java](../src/test/java/com/mortitech/completablefuture/level6_advanced/FastAuditOverheadTest.java) for benchmarks covering:

1. **Sync vs Async Overhead** - measures overhead for fast operations (5ms)
2. **thenRun vs thenRunAsync** - thread switch overhead comparison
3. **Throughput Comparison** - sync, fire-and-forget, parallel, and virtual threads
4. **Break-even Analysis** - when async overhead exceeds benefits

---

## References

1. **Java Concurrency in Practice** - Brian Goetz
2. [Baeldung - CompletableFuture and ThreadPool](https://www.baeldung.com/java-completablefuture-threadpool)
3. [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
4. [Oracle - Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)

---

*Last updated: February 5, 2026*
