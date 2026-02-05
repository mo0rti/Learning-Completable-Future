# Java CompletableFuture - Progressive Learning Path

A comprehensive, hands-on guide to mastering `CompletableFuture` in Java, from basic concepts to production-ready Spring Boot patterns.

## Overview

This project provides a **progressive learning path** with practical, real-world examples for Java's `CompletableFuture`. Each level builds upon the previous one, introducing more advanced concepts while maintaining focus on production-ready patterns.

## Requirements

- **Java 21+**
- **Maven 3.8+**
- **Spring Boot 4.x**

## 📚 Additional Resources

- **[Quick Reference Guide](docs/QUICK_REFERENCE.md)** - Fast lookup for testing approaches
- **[Testing Best Practices](docs/TESTING_BEST_PRACTICES.md)** - Comprehensive guide to testing async code
- **[Testing Philosophy](docs/TESTING_PHILOSOPHY.md)** - Deep dive into testing decisions and trade-offs

## Project Structure

```
src/main/java/com/mortitech/completablefuture/
├── domain/                     # Domain models used across examples
│   ├── User.java
│   ├── UserProfile.java
│   ├── Order.java
│   ├── Payment.java
│   └── DashboardData.java
├── level1_basics/              # Level 1: Fundamentals
├── level2_chaining/            # Level 2: Chaining & Composition
├── level3_parallel/            # Level 3: Parallel Execution
├── level4_errors/              # Level 4: Error Handling
├── level5_spring/              # Level 5: Spring Boot Integration
├── level6_advanced/            # Level 6: Advanced Patterns
└── level7_testing/             # Level 7: Testing Strategies
```

## Learning Levels

### Level 1 - Basics
**File:** `level1_basics/Level1_BasicExamples.java`

- `supplyAsync` - The starting point for async operations
- Blocking vs non-blocking patterns
- `thenAccept` and `thenApply` transformations
- `completedFuture` for cached/immediate values
- `join()` vs `get()` - when blocking is necessary

**Key Takeaway:** Understand when async provides value and when it adds unnecessary complexity.

---

### Level 2 - Chaining & Composition
**File:** `level2_chaining/Level2_ChainingExamples.java`

- `thenApply` vs `thenCompose` - the critical difference
- Avoiding nested `CompletableFuture<CompletableFuture<T>>`
- Real-world scenario: User profile enrichment
- Mixing sync and async transformations

**Key Takeaway:** Use `thenApply` for synchronous transformations, `thenCompose` for async chaining.

---

### Level 3 - Parallel Execution
**File:** `level3_parallel/Level3_ParallelExecution.java`

- `allOf()` - Wait for all futures to complete
- `anyOf()` - First result wins (racing pattern)
- `thenCombine` - Combine exactly two futures
- Custom `Executor` configuration
- Thread pool sizing guidelines
- Virtual threads (Java 21+)

**Key Takeaway:** Independent I/O operations should run in parallel. Custom executors give you control.

---

### Level 4 - Error Handling
**File:** `level4_errors/Level4_ErrorHandling.java`

- `exceptionally` - Return fallback on error
- `handle` - Transform both success and failure
- `whenComplete` - Side effects without transformation
- `exceptionallyCompose` - Async fallback (Java 12+)
- Partial failure handling in parallel operations
- Chained error handlers

**Key Takeaway:** Always handle errors at some point in your chain. Unhandled exceptions can vanish silently.

---

### Level 5 - Spring Boot Integration
**Files:** `level5_spring/`

- `AsyncConfig.java` - Executor bean configuration
- `UserService.java` - Service layer best practices
- `UserServiceWithAsync.java` - @Async pitfalls and correct usage
- `UserController.java` - Controller patterns with CompletableFuture

**Key Topics:**
- Inject executor instead of using `commonPool()`
- `@Qualifier` for specific executor selection
- Avoiding `@Async` + `CompletableFuture` misuse
- Spring MVC async support

**Key Takeaway:** Prefer manual `CompletableFuture` over `@Async` for better control and testability.

---

### Level 6 - Advanced Patterns
**Files:** `level6_advanced/`

- `Level6_AdvancedPatterns.java` - Core advanced patterns
- `FireAndForgetPatterns.java` - Safe fire-and-forget implementations
- `HighPressurePatterns.java` - Handling high load scenarios

**Topics Covered:**
- `orTimeout` / `completeOnTimeout` (Java 9+)
- Retry with exponential backoff
- Semaphore-based rate limiting
- Hedged requests (first successful wins)
- Avoiding thread starvation and deadlocks
- Virtual threads for blocking safety

**Fire-and-Forget Patterns:**
- Why fire-and-forget is dangerous (silent exception swallowing)
- Safe fire-and-forget with logging
- Fire-and-forget with retry
- Fire-and-forget with fallback
- Optionally-tracked tasks (best of both worlds)
- Real-world examples: analytics, notifications, audit logs

**High Pressure / Thread Pool Exhaustion:**
- Bounded queues vs unbounded (memory exhaustion risk)
- Rejection policies: `AbortPolicy`, `CallerRunsPolicy`, `DiscardPolicy`
- Batched database writes for high-throughput logging
- Rate limiting with `Semaphore`
- Circuit breaker pattern for failing dependencies
- Metrics tracking under load

**Key Takeaway:** Never call `join()`/`get()` inside async tasks. For fire-and-forget, always add error handling. Under high load, use bounded queues with appropriate rejection policies.

---

### Level 7 - Testing
**Files:** `level7_testing/`

- `UserServiceForTesting.java` - Testable service design
- `Level7_TestingExamplesTest.java` - Comprehensive test examples

**Testing Strategies:**
- `completedFuture()` / `failedFuture()` for mocking
- Synchronous executor for deterministic tests
- Testing timeouts and error paths
- Testing parallel execution
- Separating pure business logic from async mechanics

**Key Takeaway:** Use synchronous executors in unit tests for determinism. Test async behavior separately.

---

## Running the Project

### Build
```bash
./mvnw clean compile
```

### Run Tests
```bash
./mvnw test
```

### Run Specific Test Class
```bash
./mvnw test -Dtest=Level1_BasicExamplesTest
```

## Quick Reference

| Method | When to Use |
|--------|-------------|
| `supplyAsync` | Start async computation that returns a value |
| `runAsync` | Start async computation with no return value |
| `thenApply` | Synchronous transformation (like `map`) |
| `thenCompose` | Async transformation (like `flatMap`) |
| `thenCombine` | Combine two independent futures |
| `allOf` | Wait for all futures to complete |
| `anyOf` | Wait for first future to complete |
| `exceptionally` | Provide fallback value on error |
| `handle` | Handle both success and error |
| `whenComplete` | Side effects (logging, metrics) |
| `orTimeout` | Fail with TimeoutException |
| `completeOnTimeout` | Return default on timeout |

## Anti-Patterns to Avoid

1. **Blocking inside async tasks** - Causes thread starvation
2. **Using `commonPool()` for I/O** - Shared pool can be exhausted
3. **`thenApply` returning `CompletableFuture`** - Creates nested futures
4. **Ignoring exceptions** - They vanish silently
5. **`@Async` without executor** - Creates unbounded threads
6. **Internal `@Async` calls** - Bypasses proxy, runs synchronously
7. **Fire-and-forget without error handling** - Exceptions lost forever
8. **Unbounded queues under high load** - Leads to `OutOfMemoryError`

## Rejection Policies (ThreadPoolExecutor)

| Policy | Behavior | Use When |
|--------|----------|----------|
| `AbortPolicy` | Throws `RejectedExecutionException` | You need to know immediately when overloaded |
| `CallerRunsPolicy` | Caller thread executes task | Backpressure is acceptable |
| `DiscardPolicy` | Silently drops task | Losing tasks is acceptable (sampling) |
| `DiscardOldestPolicy` | Drops oldest, retries new | Newer data is more important |

## Thread Pool Sizing Guidelines

| Workload Type | Recommended Size |
|---------------|------------------|
| I/O-bound | CPU cores × 2-4 |
| CPU-bound | CPU cores |
| Mixed | Separate pools |
| Virtual threads | Unlimited (Java 21+) |

## License

MIT License
