# Mastering Java CompletableFuture — Part 3: Error Handling & Spring Boot Integration

*Graceful failure management, the @Async trap, and production-ready executor configuration for Spring Boot backends.*

---

## When Async Operations Fail

Here's a dirty secret about async code: **errors love to disappear.**

```java
CompletableFuture.supplyAsync(() -> {
    throw new RuntimeException("This error vanishes into the void");
});
// No exception thrown here. No stack trace. Nothing.
```

If you don't explicitly handle errors in `CompletableFuture`, they're silently swallowed. Your application continues running, but something is broken, and you have no idea.

This is why error handling in async code isn't optional—it's critical.

---

## The Error Handling Trio

`CompletableFuture` gives you three tools for handling errors. Each has a specific purpose:

| Method | Called when | Transforms result | Use case |
|--------|-------------|-------------------|----------|
| `exceptionally(fn)` | Error only | Yes | Provide fallback value |
| `handle(fn)` | Always (success OR error) | Yes | Transform both cases |
| `whenComplete(fn)` | Always (success OR error) | No | Side effects (logging, metrics) |

Let's see each in action.

---

## exceptionally — Provide a Fallback

`exceptionally` is your catch block. It's called **only when an exception occurs** and returns a fallback value.

```java
public CompletableFuture<User> fetchUserWithFallback(Long userId) {
    return fetchUserThatMayFail(userId)
        .exceptionally(throwable -> {
            // Log the error
            log.error("Failed to fetch user: {}", throwable.getMessage());
            // Return a fallback
            return User.empty();
        });
}
```

The caller receives either:
- The actual user (on success)
- An empty user (on failure)

No exception propagates—the chain continues with the fallback value.

### Handling specific exception types

Real applications have different failure modes. A "user not found" is different from "database is down":

```java
public CompletableFuture<User> fetchUserWithTypedHandling(Long userId) {
    return fetchUserAsync(userId)
        .exceptionally(throwable -> {
            // Unwrap CompletionException to get the real cause
            Throwable cause = throwable.getCause() != null
                ? throwable.getCause()
                : throwable;

            if (cause instanceof UserNotFoundException) {
                // Expected case - return empty user
                return User.empty();
            } else if (cause instanceof ServiceUnavailableException) {
                // Infrastructure issue - rethrow for circuit breaker
                throw new RuntimeException("Service temporarily unavailable", cause);
            } else {
                // Unknown error - rethrow
                throw new RuntimeException("Unexpected error", cause);
            }
        });
}
```

**Important:** Exceptions in `CompletableFuture` are wrapped in `CompletionException`. Always check `getCause()` to get the actual exception type.

---

## handle — Transform Success and Failure

`handle` is called for **both** success and failure. It's more flexible than `exceptionally` when you need to transform the result in both cases.

Perfect for the Result pattern:

```java
public record UserResult(boolean success, User user, String errorMessage) {
    public static UserResult success(User user) {
        return new UserResult(true, user, null);
    }
    public static UserResult failure(String errorMessage) {
        return new UserResult(false, null, errorMessage);
    }
}

public CompletableFuture<UserResult> fetchUserAsResult(Long userId) {
    return fetchUserAsync(userId)
        .handle((user, throwable) -> {
            if (throwable != null) {
                return UserResult.failure(throwable.getMessage());
            }
            return UserResult.success(user);
        });
}
```

Now the caller always gets a `UserResult`—no exception handling needed:

```java
UserResult result = fetchUserAsResult(123L).join();
if (result.success()) {
    processUser(result.user());
} else {
    showError(result.errorMessage());
}
```

---

## whenComplete — Side Effects Only

`whenComplete` is for **side effects**: logging, metrics, cleanup. It doesn't transform the result or swallow exceptions.

```java
public CompletableFuture<User> fetchUserWithLogging(Long userId) {
    return fetchUserAsync(userId)
        .whenComplete((user, throwable) -> {
            if (throwable != null) {
                log.error("ERROR fetching user {}: {}", userId, throwable.getMessage());
                metrics.increment("user.fetch.error");
            } else {
                log.info("Successfully fetched user: {}", user.username());
                metrics.increment("user.fetch.success");
            }
        });
    // The original result (or exception) passes through unchanged
}
```

**Key difference from `handle`:** If there was an error, it still propagates. `whenComplete` observes but doesn't interfere.

---

## Fallback to Backup Service

A common pattern: try the primary service, fall back to backup on failure.

### The wrong way (nested futures)

```java
// ❌ WRONG: Returns CompletableFuture<CompletableFuture<User>>
return fetchFromPrimary(userId)
    .exceptionally(ex -> fetchFromBackup(userId));  // Returns CF, not User!
```

### The right way (exceptionallyCompose)

```java
// ✅ CORRECT: exceptionallyCompose flattens the future
public CompletableFuture<User> fetchUserWithBackup(Long userId) {
    return fetchFromPrimaryService(userId)
        .exceptionallyCompose(throwable -> {
            log.warn("Primary failed, trying backup: {}", throwable.getMessage());
            return fetchFromBackupService(userId);
        });
}
```

`exceptionallyCompose` (Java 12+) is to `exceptionally` what `thenCompose` is to `thenApply`. Use it when your fallback is itself async.

---

## Partial Failure in Parallel Operations

When running multiple operations in parallel, **some may succeed while others fail**. Don't lose the successful results just because one operation failed.

```java
public CompletableFuture<DashboardResult> loadDashboardWithPartialFailure(Long userId) {
    // Each future handles its own errors
    CompletableFuture<DataResult<User>> userFuture =
        fetchUser(userId)
            .handle((user, ex) -> ex != null
                ? DataResult.failure("user", ex.getMessage())
                : DataResult.success("user", user));

    CompletableFuture<DataResult<String>> ordersFuture =
        fetchOrdersSummary(userId)
            .handle((summary, ex) -> ex != null
                ? DataResult.failure("orders", ex.getMessage())
                : DataResult.success("orders", summary));

    CompletableFuture<DataResult<String>> paymentsFuture =
        fetchPaymentsSummary(userId)
            .handle((summary, ex) -> ex != null
                ? DataResult.failure("payments", ex.getMessage())
                : DataResult.success("payments", summary));

    // Combine all results, including partial failures
    return CompletableFuture.allOf(userFuture, ordersFuture, paymentsFuture)
        .thenApply(ignored -> new DashboardResult(
            userFuture.join(),
            ordersFuture.join(),
            paymentsFuture.join()
        ));
}
```

Now your dashboard can show:
- User data: loaded
- Orders: "Service temporarily unavailable"
- Payments: loaded

Much better than a blank screen.

---

## Chaining Error Handlers

You can chain multiple error handlers for different concerns:

```java
public CompletableFuture<User> fetchUserWithChainedHandling(Long userId) {
    return fetchUserThatMayFail(userId)
        // First: Log (side effect only)
        .whenComplete((user, ex) -> {
            if (ex != null) {
                log.error("Logging error: {}", ex.getMessage());
            }
        })
        // Second: Try backup service
        .exceptionallyCompose(ex -> {
            log.info("Attempting backup recovery...");
            return fetchUserFromBackupService(userId);
        })
        // Third: If backup also fails, return empty
        .exceptionally(ex -> {
            log.error("All sources failed, returning empty user");
            return User.empty();
        });
}
```

The chain:
1. `whenComplete` logs the error but doesn't stop it
2. `exceptionallyCompose` tries the backup
3. `exceptionally` provides final fallback if backup also fails

---

## Spring Boot Integration

Now let's put this into a real Spring Boot application.

### Step 1: Configure Custom Executors

Never use `ForkJoinPool.commonPool()` in production. Configure dedicated executors:

```java
@Configuration
@EnableAsync  // Only if you use @Async annotation
public class AsyncConfig {

    @Bean(name = "ioTaskExecutor")
    public Executor ioTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int cpuCores = Runtime.getRuntime().availableProcessors();

        // I/O-bound: threads spend time waiting, so use more
        executor.setCorePoolSize(cpuCores * 2);
        executor.setMaxPoolSize(cpuCores * 4);
        executor.setQueueCapacity(100);

        // CRITICAL: Named threads for debugging
        executor.setThreadNamePrefix("io-async-");

        // Backpressure: caller runs task when pool exhausted
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Graceful shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    @Bean(name = "cpuTaskExecutor")
    public Executor cpuTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int cpuCores = Runtime.getRuntime().availableProcessors();

        // CPU-bound: no benefit from more threads than cores
        executor.setCorePoolSize(cpuCores);
        executor.setMaxPoolSize(cpuCores);
        executor.setQueueCapacity(50);

        executor.setThreadNamePrefix("cpu-async-");
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    // Java 21+ Virtual Threads
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

### Key configuration decisions:

| Setting | Purpose |
|---------|---------|
| `corePoolSize` | Minimum threads always ready |
| `maxPoolSize` | Maximum threads when queue is full |
| `queueCapacity` | Tasks waiting before creating new threads |
| `threadNamePrefix` | Makes debugging possible |
| `CallerRunsPolicy` | Backpressure when overwhelmed |

---

## Service Layer: Manual CompletableFuture

This is the **recommended approach**. Full control, clear code flow, easy testing.

```java
@Service
public class UserService {

    private final Executor ioExecutor;
    private final ExternalUserClient userClient;
    private final PreferenceService preferenceService;
    private final LoyaltyService loyaltyService;

    public UserService(
            @Qualifier("ioTaskExecutor") Executor ioExecutor,
            ExternalUserClient userClient,
            PreferenceService preferenceService,
            LoyaltyService loyaltyService) {
        this.ioExecutor = ioExecutor;
        this.userClient = userClient;
        this.preferenceService = preferenceService;
        this.loyaltyService = loyaltyService;
    }

    // Simple async operation
    public CompletableFuture<User> findUserAsync(Long userId) {
        return CompletableFuture.supplyAsync(
            () -> userClient.fetchUser(userId),
            ioExecutor
        );
    }

    // Parallel fetching
    public CompletableFuture<UserProfile> getUserProfileAsync(Long userId) {
        CompletableFuture<User> userFuture = findUserAsync(userId);

        CompletableFuture<List<String>> prefsFuture =
            CompletableFuture.supplyAsync(
                () -> preferenceService.getPreferences(userId),
                ioExecutor
            );

        CompletableFuture<Integer> pointsFuture =
            CompletableFuture.supplyAsync(
                () -> loyaltyService.getPoints(userId),
                ioExecutor
            );

        return CompletableFuture.allOf(userFuture, prefsFuture, pointsFuture)
            .thenApplyAsync(ignored -> {
                return UserProfile.of(
                    userFuture.join(),
                    prefsFuture.join(),
                    LocalDateTime.now(),
                    pointsFuture.join(),
                    calculatePlan(userFuture.join().tier(), pointsFuture.join())
                );
            }, ioExecutor);
    }

    // With error handling
    public CompletableFuture<User> findUserWithFallbackAsync(Long userId) {
        return findUserAsync(userId)
            .exceptionallyAsync(throwable -> {
                log.error("Failed to fetch user {}: {}", userId, throwable.getMessage());
                return User.empty();
            }, ioExecutor);
    }
}
```

**Notice:**
- Executor is injected via constructor
- `@Qualifier` selects the specific executor bean
- Every async operation explicitly uses the executor
- The `*Async` suffix in method names indicates async behavior

---

## The @Async Trap

Spring's `@Async` annotation seems convenient, but it has several pitfalls.

### Pitfall 1: Default executor creates unbounded threads

```java
@Async  // Uses SimpleAsyncTaskExecutor by default!
public CompletableFuture<User> findUser(Long userId) {
    return CompletableFuture.completedFuture(userClient.fetchUser(userId));
}
```

`SimpleAsyncTaskExecutor` creates a **new thread for every call**. Under load, this exhausts system resources.

**Fix:** Always specify the executor:

```java
@Async("ioTaskExecutor")
public CompletableFuture<User> findUser(Long userId) { ... }
```

### Pitfall 2: Internal calls bypass the proxy

```java
@Service
public class UserService {

    @Async("ioTaskExecutor")
    public CompletableFuture<User> findUserAsync(Long userId) {
        return CompletableFuture.completedFuture(userClient.fetchUser(userId));
    }

    public User findUserSync(Long userId) {
        // ❌ THIS DOES NOT WORK!
        // Internal calls bypass Spring's proxy
        CompletableFuture<User> future = findUserAsync(userId);
        return future.join();  // Runs synchronously!
    }
}
```

`@Async` works through Spring's proxy mechanism. When you call a method from within the same class, you bypass the proxy—the annotation is ignored.

### Pitfall 3: Void return swallows exceptions

```java
@Async("ioTaskExecutor")
public void sendNotification(Long userId, String message) {
    // If this throws, the exception is SILENTLY SWALLOWED
    notificationService.send(userId, message);
}
```

Unless you configure `AsyncUncaughtExceptionHandler`, exceptions in `void` async methods vanish.

### When to use @Async

- Simple fire-and-forget operations where you don't need the result
- When the annotation's simplicity outweighs its limitations
- When all callers are external to the class

### When to avoid @Async

- Complex async workflows with chaining
- When you need fine-grained error handling
- When methods might be called internally
- When you want explicit, testable code

**My recommendation:** Prefer manual `CompletableFuture`. The explicit code is clearer, more testable, and has no proxy magic.

---

## Controller Layer

Controllers should return `CompletableFuture` (or better, use reactive types with WebFlux). Spring handles the async response automatically.

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public CompletableFuture<ResponseEntity<User>> getUser(@PathVariable Long id) {
        return userService.findUserAsync(id)
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/profile")
    public CompletableFuture<ResponseEntity<UserProfile>> getProfile(@PathVariable Long id) {
        return userService.getUserProfileAsync(id)
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> {
                log.error("Failed to load profile for user {}", id, ex);
                return ResponseEntity.internalServerError().build();
            });
    }
}
```

Spring's async support:
1. Returns immediately (doesn't block the servlet thread)
2. Completes the HTTP response when the future completes
3. Handles exceptions via the `exceptionally` handler

---

## Error Handling Quick Reference

```java
// Fallback value on error
.exceptionally(ex -> defaultValue)

// Async fallback (returns CompletableFuture)
.exceptionallyCompose(ex -> fallbackAsync())

// Transform both success and failure
.handle((result, ex) -> transform(result, ex))

// Side effects only (logging, metrics)
.whenComplete((result, ex) -> logAndMetric(result, ex))

// Chain: log → try backup → final fallback
.whenComplete((r, ex) -> log(ex))
.exceptionallyCompose(ex -> tryBackup())
.exceptionally(ex -> finalFallback)
```

---

## Key Takeaways

1. **Always handle errors explicitly.** Unhandled async errors disappear silently.

2. **Use the right tool:** `exceptionally` for fallbacks, `handle` for transformation, `whenComplete` for side effects.

3. **Handle partial failures.** Don't let one failed operation bring down the entire response.

4. **Configure custom executors.** Name your threads, size pools appropriately, set rejection policies.

5. **Prefer manual CompletableFuture over @Async.** It's more explicit, testable, and has no proxy surprises.

6. **Always specify the executor.** `@Async` without an executor name uses unbounded thread creation.

---

## Coming Up: Part 4 — Production Patterns & Testing

We've covered the fundamentals. In Part 4, we'll tackle the advanced stuff:

- Fire-and-forget: when to use it, how to make it safe
- High-pressure handling: what happens when you're overwhelmed
- Thread pool exhaustion and how to prevent it
- Testing async code without flaky tests

See you there.

---

*[← Part 2: Composition](./part2-composition.md) | [Part 4: Production Patterns →](./part4-production.md)*
