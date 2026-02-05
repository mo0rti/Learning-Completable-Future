# Testing CompletableFuture - Best Practices

This document explains different approaches for testing asynchronous CompletableFuture operations and why some are better than others.

## 🚫 Avoid: Thread.sleep()

```java
@Test
void testAsync_WithThreadSleep() throws InterruptedException {
    paymentService.process(userId, amount);
    Thread.sleep(2000); // ❌ BAD: Arbitrary timeout, unreliable
    verify(auditRepository).save(any(AuditLog.class));
}
```

**Problems:**
- ⏱️ **Slow tests**: Always waits full timeout even if operation completes faster
- ❌ **Unreliable**: Might fail on slower machines if timeout is too short
- 🎲 **Non-deterministic**: No guarantee async operation actually completed
- 🐛 **False positives**: Test might pass even if async code has bugs

---

## ⚠️ Better: Awaitility Library

```java
@Test
void testAsync_WithAwaitility() {
    paymentService.process(userId, amount);
    await().atMost(2, SECONDS)
        .untilAsserted(() -> verify(auditRepository).save(any(AuditLog.class)));
}
```

**Pros:**
- ✅ Polls repeatedly until condition is met
- ✅ Fails fast if condition never becomes true
- ✅ More reliable than Thread.sleep()

**Cons:**
- 📦 Requires external dependency
- ⏱️ Still uses polling with arbitrary timeout
- 🔍 Harder to debug when it fails

---

## ✅ Best: Return CompletableFuture and use .join()

```java
// Service method - return the CompletableFuture
public CompletableFuture<Void> processAsync(String userId, BigDecimal amount) {
    // Process payment...
    return CompletableFuture.runAsync(() -> {
        // Async operation
    });
}

// Fire-and-forget wrapper for production use
public void process(String userId, BigDecimal amount) {
    processAsync(userId, amount); // Don't wait for result
}

// Test - deterministic waiting
@Test
void testAsync_WithJoin() {
    CompletableFuture<Void> future = paymentService.processAsync(userId, amount);
    future.join(); // ✅ Waits exactly until completion
    verify(auditRepository).save(any(AuditLog.class));
}
```

**Advantages:**
- ✅ **Deterministic**: Waits exactly until completion, no arbitrary timeout
- ✅ **Fast**: Returns immediately when async operation completes
- ✅ **No dependencies**: Uses standard Java API
- ✅ **Production-friendly**: Still allows fire-and-forget via wrapper method
- ✅ **Testable**: Tests have full control over async lifecycle
- ✅ **Clean API**: Clear separation between fire-and-forget and testable versions

---

## 📊 Comparison Table

| Approach | Speed | Reliability | Dependencies | Deterministic | Production Impact |
|----------|-------|-------------|--------------|---------------|-------------------|
| Thread.sleep() | ❌ Slowest | ❌ Unreliable | ✅ None | ❌ No | ✅ None |
| Awaitility | ⚠️ Medium | ✅ Good | ❌ External lib | ⚠️ Polling | ✅ None |
| .join() | ✅ Fast | ✅ Best | ✅ None | ✅ Yes | ⚠️ API change* |

*API change is minimal - wrap in fire-and-forget method

---

## 🎯 Recommendation

**Use the CompletableFuture return pattern:**

1. **Create a method that returns CompletableFuture** - for testing
2. **Wrap it in a void method** - for production fire-and-forget usage
3. **Test using .join()** - deterministic and fast

This gives you:
- ✅ Best testing experience
- ✅ Production fire-and-forget behavior
- ✅ No external dependencies
- ✅ Fast, deterministic tests

---

## 💡 Alternative: CompletableFuture.get() with timeout

For safety, you can use `.get()` with a timeout:

```java
@Test
void testAsync_WithGetTimeout() throws Exception {
    CompletableFuture<Void> future = paymentService.processAsync(userId, amount);
    future.get(5, TimeUnit.SECONDS); // Fail-safe timeout
    verify(auditRepository).save(any(AuditLog.class));
}
```

This combines benefits of both approaches:
- Waits deterministically until completion
- Has a fail-safe timeout to prevent test hangs
- Useful when async operations might deadlock

---

## 📝 Summary

**For learning CompletableFuture:**
- Use `.join()` or `.get()` with returned CompletableFuture
- This is the most idiomatic and best practice approach
- No external libraries needed
- Tests are fast, reliable, and deterministic

**Avoid:**
- Thread.sleep() for production code
- Awaitility if you can return CompletableFuture instead
