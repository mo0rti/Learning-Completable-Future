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
- 🎲 **Flaky tests**: Can produce both false positives (passes when it shouldn't) and false negatives (fails when code is correct)

---

## ⚠️ Better: Mockito.timeout()

```java
@Test
void testAsync_WithMockitoTimeout() {
    paymentService.process(userId, amount);

    // Poll for up to 2 seconds, checking every 10ms (default polling interval)
    verify(auditRepository, timeout(2000)).save(any(AuditLog.class));
}
```

**Pros:**
- ✅ Polls repeatedly until condition is met
- ✅ Succeeds fast - returns immediately when condition becomes true
- ✅ No external dependency (Mockito likely already present)
- ✅ More reliable than Thread.sleep()
- ✅ Built-in Mockito feature

**Cons:**
- ⏱️ Still uses polling with arbitrary timeout
- ⏱️ Slower than `.join()` due to polling overhead
- 🔍 Not truly deterministic (10ms default polling interval)
- ⏱️ Waits full timeout duration before failing if condition is never met

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
    await().atMost(2, SECONDS)  // Default: 10 seconds, polling every 100ms
        .untilAsserted(() -> verify(auditRepository).save(any(AuditLog.class)));
}
```

**Pros:**
- ✅ Polls repeatedly until condition is met
- ✅ Succeeds fast - returns immediately when condition becomes true
- ✅ More reliable than Thread.sleep()
- ✅ More expressive DSL than Mockito.timeout()
- ✅ Good debugging with `ConditionEvaluationLogger` and deadlock detection

**Cons:**
- 📦 Requires external dependency
- ⏱️ Still uses polling with arbitrary timeout (default: 100ms interval, 10s timeout)
- ⏱️ Waits full timeout duration before failing if condition is never met

**When to Use:**
- ✅ When Mockito.timeout() isn't flexible enough
- ✅ Complex polling conditions beyond simple verification
- ✅ Need custom polling intervals or conditions
- ✅ Need detailed condition evaluation logging

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

**Scope limitation:** This pattern works when you test the async method directly. See the next section for testing methods that *call* async methods internally.

---

## ✅ Two-Layer Testing: When Sync Method Calls Async Method

A common real-world scenario: a synchronous method that triggers an async side-effect (e.g., payment processing that fires off an audit log).

```java
// Main business method (synchronous)
public void processPayment(String userId, BigDecimal amount) {
    // Payment logic...
    Payment payment = createPayment(userId, amount);

    // Fire-and-forget audit - future is DISCARDED here
    logAuditAsync(createAuditLog(payment));
}

// Async method that returns CompletableFuture
public CompletableFuture<Void> logAuditAsync(AuditLog log) {
    return CompletableFuture.runAsync(() -> {
        auditRepository.save(log);
    }, executor);
}
```

**The problem:** When you call `processPayment()` in a test, you have no handle to the `CompletableFuture` - it's created and discarded inside the method.

### Solution: Two-Layer Testing ✅

**Layer 1: Verify the integration** (sync method calls async method)
```java
@Test
void processPayment_shouldTriggerAuditLog() {
    // Arrange
    PaymentService spyService = spy(paymentService);

    // Act
    spyService.processPayment(userId, amount);

    // Assert - verify the async method was called with correct args
    verify(spyService).logAuditAsync(argThat(log ->
        log.getUserId().equals(userId) &&
        log.getAction().equals("PAYMENT")
    ));
}
```

**Layer 2: Test the async method with `.join()`** (deterministic)
```java
@Test
void logAuditAsync_shouldSaveToRepository() {
    // Arrange
    AuditLog log = new AuditLog(userId, "PAYMENT", details);

    // Act
    CompletableFuture<Void> future = paymentService.logAuditAsync(log);
    future.join();  // ✅ Deterministic wait

    // Assert
    verify(auditRepository).save(log);
}
```

### Why Two Layers?

| Layer | What it tests | How | Deterministic? |
|-------|--------------|-----|----------------|
| **Layer 1** | Integration: "Does processPayment call logAuditAsync?" | Spy + verify | ✅ Yes |
| **Layer 2** | Behavior: "Does logAuditAsync save correctly?" | `.join()` | ✅ Yes |

**Combined coverage:**
- ✅ Verifies `processPayment()` triggers the audit
- ✅ Verifies `logAuditAsync()` saves correctly
- ✅ Both tests are deterministic (no polling)
- ✅ Fast execution

### When to Use Two-Layer Testing

Use this pattern when:
- ✅ A sync method calls an async method internally
- ✅ The async method returns `CompletableFuture` but caller discards it
- ✅ You want deterministic tests (no `Mockito.timeout()`)
- ✅ You need to verify both the integration AND the async behavior

---

## 📊 Comparison Table

| Approach | Speed | Reliability | Dependencies | Deterministic | Production Impact | When to Use |
|----------|-------|-------------|--------------|---------------|-------------------|-------------|
| Thread.sleep() | ❌ Slowest | ❌ Unreliable | ✅ None | ❌ No | ✅ None | ❌ **NEVER** |
| **Mockito.timeout()** | ⚠️ Medium | ✅ Good | ⚠️ Mockito (common) | ⚠️ Polling (10ms) | ✅ None | ⚠️ **Can't change production** |
| Awaitility | ⚠️ Medium | ✅ Good | ❌ External lib | ⚠️ Polling (100ms) | ✅ None | ⚠️ **Complex conditions** |
| .join() | ✅ **Fast** | ✅ **Best** | ✅ None | ✅ **Yes** | ⚠️ API change* | ✅ **ALWAYS prefer** |

*API change is minimal - wrap in fire-and-forget method

**Note:** Polling intervals are defaults. Mockito polls every 10ms, Awaitility every 100ms. Both succeed immediately when conditions are met but wait full timeout before failing.

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

**`.join()` vs `.get()` - Key Difference:**

| Method | Exception Type | Requires try-catch | Timeout Support |
|--------|---------------|-------------------|-----------------|
| `.join()` | Unchecked (`CompletionException`) | No | No |
| `.get()` | Checked (`ExecutionException`, `InterruptedException`) | Yes | No |
| `.get(timeout, unit)` | Checked (+ `TimeoutException`) | Yes | Yes |

**Recommendation:** Use `.join()` for cleaner test code. Use `.get(timeout, unit)` only when you need fail-safe timeout protection against potential deadlocks.

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
