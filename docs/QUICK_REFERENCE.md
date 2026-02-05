# Testing CompletableFuture - Quick Reference Guide

## 🚦 Decision Tree

```
┌─ Can you modify the production code to return CompletableFuture?
│
├─ ✅ YES
│  └─ Return CompletableFuture<Void> + use .join() in tests
│     └─ Wrap in void method for fire-and-forget if needed
│        └─ BEST APPROACH ⭐
│
└─ ❌ NO (legacy/third-party code)
   └─ Use Mockito.timeout() in tests
      └─ Plan to refactor later when possible
         └─ ACCEPTABLE FALLBACK ⚠️
```

---

## 📋 Quick Comparison

| Approach | Use When | Avoid When | Speed | Reliability |
|----------|----------|------------|-------|-------------|
| **Return Future + .join()** | ✅ Always (if possible) | Never | ⚡ Fast | 💯 Best |
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
    verify(repo, timeout(2000)).save(any()); // ⚠️ Polling
}
```

---

## 🎯 When to Use What

### Use `.join()` when:
- ✅ Writing new code
- ✅ Can modify production methods
- ✅ Want deterministic tests
- ✅ Want fast tests
- ✅ Learning CompletableFuture

### Use `Mockito.timeout()` when:
- ⚠️ Cannot modify production code
- ⚠️ Testing legacy systems
- ⚠️ Testing third-party libraries
- ⚠️ Integration tests with side effects
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

1. **🥇 Return CompletableFuture + .join()** - Always prefer
2. **🥈 Mockito.timeout()** - When #1 impossible
3. **🥉 Awaitility** - When #2 insufficient
4. **💀 Thread.sleep()** - Never acceptable

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
