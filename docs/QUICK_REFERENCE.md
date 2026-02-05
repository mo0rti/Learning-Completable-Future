# Testing CompletableFuture - Quick Reference Guide

## 🚦 Decision Tree

```
┌─ Does the async method return CompletableFuture?
│
├─ ✅ YES
│  │
│  ├─ Can you call it directly in tests?
│  │  └─ ✅ YES → Use .join() directly ⭐ BEST
│  │
│  └─ Is it called internally by a sync method?
│     └─ ✅ YES → Use TWO-LAYER TESTING ⭐
│        ├─ Layer 1: spy + verify the call
│        └─ Layer 2: test async method with .join()
│
└─ ❌ NO (legacy/third-party void method)
   └─ Use Mockito.timeout() ⚠️ FALLBACK
      └─ Plan to refactor later
```

---

## 📋 Quick Comparison

| Approach | Use When | Avoid When | Speed | Reliability |
|----------|----------|------------|-------|-------------|
| **Return Future + .join()** | ✅ Test async method directly | Never | ⚡ Fast | 💯 Best |
| **Two-Layer Testing** | ✅ Sync method calls async internally | Overkill for simple cases | ⚡ Fast | 💯 Best |
| **Mockito.timeout()** | ⚠️ Can't change code | Can return Future | 🐢 Medium | ✅ Good |
| **Awaitility** | ⚠️ Complex conditions | Mockito sufficient | 🐢 Medium | ✅ Good |
| **Thread.sleep()** | ❌ NEVER | ❌ ALWAYS | 🐌 Slow | ❌ Bad |

---

## 💻 Code Templates

### ✅ BEST: Return CompletableFuture

```java
// Production code
public CompletableFuture<Void> processAsync(Data data) {
    return CompletableFuture.runAsync(() -> {
        // Your async logic
    });
}

// Fire-and-forget wrapper (optional)
public void process(Data data) {
    processAsync(data); // Don't wait
}

// Test
@Test
void test() {
    CompletableFuture<Void> future = service.processAsync(data);
    future.join(); // ⚡ Deterministic wait
    verify(repo).save(any());
}
```

### ✅ TWO-LAYER: Sync method calls async method internally

```java
// Production code
public void processPayment(String userId, BigDecimal amount) {
    // Sync logic...
    logAuditAsync(log);  // Future discarded (fire-and-forget)
}

public CompletableFuture<Void> logAuditAsync(AuditLog log) {
    return CompletableFuture.runAsync(() -> repo.save(log));
}

// Test Layer 1: Verify integration
@Test
void processPayment_callsAudit() {
    PaymentService spy = spy(service);
    spy.processPayment(userId, amount);
    verify(spy).logAuditAsync(any());  // ⚡ Deterministic
}

// Test Layer 2: Test async method directly
@Test
void logAuditAsync_savesToDb() {
    service.logAuditAsync(log).join();  // ⚡ Deterministic
    verify(repo).save(log);
}
```

### ⚠️ FALLBACK: Mockito.timeout()

```java
// Production code (can't change this)
public void process(Data data) {
    CompletableFuture.runAsync(() -> {
        // Your async logic
    });
}

// Test
@Test
void test() {
    service.process(data);
    // Polls every 10ms (default), succeeds immediately when condition met
    // Waits full 2000ms before failing if condition never becomes true
    verify(repo, timeout(2000)).save(any());
}
```

---

## 🎯 When to Use What

### Use `.join()` when:
- ✅ Testing async method directly
- ✅ Writing new code
- ✅ Can modify production methods
- ✅ Want deterministic tests
- ✅ Want fast tests

### Use Two-Layer Testing when:
- ✅ Sync method calls async method internally
- ✅ Async method returns `CompletableFuture` but caller discards it
- ✅ Want deterministic tests (no polling)
- ✅ Need to verify both integration AND async behavior

### Use `Mockito.timeout()` when:
- ⚠️ Cannot modify production code
- ⚠️ Testing legacy systems
- ⚠️ Testing third-party libraries
- ⚠️ Prefer single integration test over two-layer
- ⚠️ Planning to refactor later

### Use `Awaitility` when:
- ⚠️ Mockito.timeout() insufficient
- ⚠️ Complex polling conditions
- ⚠️ Custom polling intervals needed
- ⚠️ Already using Awaitility elsewhere

### NEVER use `Thread.sleep()`:
- ❌ Slow tests
- ❌ Unreliable tests
- ❌ Non-deterministic tests
- ❌ No excuse for this

---

## 🔗 Quick Links

- **CompletableFuture API**: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html
- **Mockito.timeout()**: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#22
- **Spring @Async**: https://spring.io/guides/gs/async-method/
- **Full Documentation**: See TESTING_BEST_PRACTICES.md and TESTING_PHILOSOPHY.md

---

## ⚡ Common Pitfalls

### ❌ Don't:
```java
@Test
void test() {
    service.process(data);
    Thread.sleep(2000); // BAD!
    verify(repo).save(any());
}
```

### ✅ Do:
```java
@Test
void test() {
    CompletableFuture<Void> future = service.processAsync(data);
    future.join(); // GOOD!
    verify(repo).save(any());
}
```

### ⚠️ Or (if can't change production):
```java
@Test
void test() {
    service.process(data);
    verify(repo, timeout(2000)).save(any()); // ACCEPTABLE
}
```

---

## 📊 Priority Ranking

1. **🥇 Return CompletableFuture + .join()** - Test async method directly
2. **🥇 Two-Layer Testing** - When sync method calls async internally
3. **🥈 Mockito.timeout()** - When #1 and #2 impossible
3. **🥉 Awaitility** - When #2 insufficient
4. **💀 Thread.sleep()** - Never acceptable

---

## 🔧 Technical Details

| Library | Default Polling | Default Timeout | Behavior |
|---------|-----------------|-----------------|----------|
| **Mockito.timeout()** | 10ms | User-specified | Succeeds fast, waits full timeout to fail |
| **Awaitility** | 100ms | 10 seconds | Succeeds fast, waits full timeout to fail |

**Key insight:** Both polling approaches return immediately when the condition is met, but wait the full timeout duration before failing if the condition is never satisfied.

---

## 💡 Pro Tips

1. **Always return CompletableFuture if you can** - Even if "fire-and-forget", return it anyway
2. **Wrap in void method** - For fire-and-forget convenience
3. **Use .join() in tests** - Deterministic and fast
4. **Mockito.timeout() for legacy** - But plan to refactor
5. **Never Thread.sleep()** - There's always a better way

---

*Quick reference for Testing CompletableFuture*
*Last updated: February 5, 2026*
