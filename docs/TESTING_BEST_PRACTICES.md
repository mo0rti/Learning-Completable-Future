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

## ⚠️ Better: Mockito.timeout()

```java
@Test
void testAsync_WithMockitoTimeout() {
    paymentService.process(userId, amount);
    
    // Poll for up to 2 seconds, checking every 100ms (default)
    verify(auditRepository, timeout(2000)).save(any(AuditLog.class));
}
```

**Pros:**
- ✅ Polls repeatedly until condition is met
- ✅ Fails fast if condition never becomes true
- ✅ No external dependency (Mockito likely already present)
- ✅ More reliable than Thread.sleep()
- ✅ Built-in Mockito feature

**Cons:**
- ⏱️ Still uses polling with arbitrary timeout
- ⏱️ Slower than `.join()` due to polling overhead
- 🔍 Not truly deterministic (100ms polling interval)

**When to Use:**
- ✅ Legacy code where you cannot modify production methods
- ✅ Third-party libraries with void async methods
- ✅ Integration tests verifying side effects

**Reference:** [Mockito.timeout() JavaDoc](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#22)

---

## ⚠️ Alternative: Awaitility Library

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
- ✅ More expressive DSL than Mockito.timeout()

**Cons:**
- 📦 Requires external dependency
- ⏱️ Still uses polling with arbitrary timeout
- 🔍 Harder to debug when it fails

**When to Use:**
- ✅ When Mockito.timeout() isn't flexible enough
- ✅ Complex polling conditions beyond simple verification
- ✅ Need custom polling intervals or conditions

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

| Approach | Speed | Reliability | Dependencies | Deterministic | Production Impact | When to Use |
|----------|-------|-------------|--------------|---------------|-------------------|-------------|
| Thread.sleep() | ❌ Slowest | ❌ Unreliable | ✅ None | ❌ No | ✅ None | ❌ **NEVER** |
| **Mockito.timeout()** | ⚠️ Medium | ✅ Good | ⚠️ Mockito (common) | ⚠️ Polling | ✅ None | ⚠️ **Can't change production** |
| Awaitility | ⚠️ Medium | ✅ Good | ❌ External lib | ⚠️ Polling | ✅ None | ⚠️ **Complex conditions** |
| .join() | ✅ **Fast** | ✅ **Best** | ✅ None | ✅ **Yes** | ⚠️ API change* | ✅ **ALWAYS prefer** |

*API change is minimal - wrap in fire-and-forget method

---

## 🎯 Recommendation

### **Primary Approach: CompletableFuture Return Pattern** ✅

1. **Create a method that returns CompletableFuture** - for testing
2. **Wrap it in a void method** - for production fire-and-forget usage
3. **Test using .join()** - deterministic and fast

This gives you:
- ✅ Best testing experience
- ✅ Production fire-and-forget behavior
- ✅ No external dependencies
- ✅ Fast, deterministic tests

### **Fallback: When You Can't Change Production Code** ⚠️

If modifying production code isn't feasible (legacy systems, third-party libraries):

```java
@Test
void testAsync_LegacyCode() {
    legacyService.processAsync(data); // Can't change this void method
    
    // Use Mockito.timeout() as pragmatic compromise
    verify(repository, timeout(2000)).save(any());
}
```

**Use Mockito.timeout() ONLY when:**
- Cannot return CompletableFuture from production code
- Testing legacy or third-party code
- Planning to refactor later

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

**Testing Decision Tree:**
```
Can you modify the production code?
├─ YES: Return CompletableFuture<Void> + use .join() ✅ BEST
│
└─ NO: Use Mockito.timeout() ⚠️ ACCEPTABLE
       (But plan to refactor later)
```

**Avoid:**
- Thread.sleep() - Always unacceptable
- Awaitility - Only if Mockito.timeout() isn't flexible enough

---

## 📚 References

1. **Oracle CompletableFuture API**: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html
2. **Mockito.timeout() Documentation**: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#22
3. **Spring @Async Guide**: https://spring.io/guides/gs/async-method/
4. **Baeldung - Mockito Verify**: https://www.baeldung.com/mockito-verify
5. **Java Concurrency in Practice** by Brian Goetz
6. **Effective Java (3rd Edition)** by Joshua Bloch - Item 81
