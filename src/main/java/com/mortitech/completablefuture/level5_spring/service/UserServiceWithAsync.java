package com.mortitech.completablefuture.level5_spring.service;

import com.mortitech.completablefuture.domain.User;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * @Async ANNOTATION: Pitfalls and Proper Usage
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * @Async is convenient but has several gotchas. This class demonstrates
 * both correct usage and common mistakes.
 *
 * KEY RULES:
 * 1. @Async only works on PUBLIC methods
 * 2. @Async only works when called from OUTSIDE the class (proxy limitation)
 * 3. Return CompletableFuture<T>, not raw T (unless fire-and-forget)
 * 4. @EnableAsync must be present in configuration
 * 5. Specify executor with @Async("executorBeanName")
 *
 * RECOMMENDATION: Prefer manual CompletableFuture over @Async for:
 * - Better control over error handling
 * - Clearer code flow
 * - Easier testing
 * - No proxy magic surprises
 */
@Service
public class UserServiceWithAsync {

    private final ExternalUserClient externalUserClient;

    public UserServiceWithAsync(ExternalUserClient externalUserClient) {
        this.externalUserClient = externalUserClient;
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ✅ CORRECT: @Async with CompletableFuture return type
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Specify the executor bean name to avoid using SimpleAsyncTaskExecutor
     * (which creates a new thread for each task - not pooled!).
     */
    @Async("ioTaskExecutor")
    public CompletableFuture<User> findUserCorrect(Long userId) {
        // Method body runs on the specified executor
        User user = externalUserClient.fetchUser(userId);
        return CompletableFuture.completedFuture(user);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ⚠️ PROBLEMATIC: @Async without executor specification
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Without specifying executor, Spring uses SimpleAsyncTaskExecutor by default,
     * which creates unbounded threads - potential resource exhaustion!
     */
    @Async  // Uses SimpleAsyncTaskExecutor - creates new thread per call!
    public CompletableFuture<User> findUserNoExecutor(Long userId) {
        User user = externalUserClient.fetchUser(userId);
        return CompletableFuture.completedFuture(user);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ❌ WRONG: @Async with void return - fire and forget
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Exceptions are SILENTLY SWALLOWED unless you configure AsyncUncaughtExceptionHandler.
     * Only use this for true fire-and-forget scenarios (logging, analytics).
     */
    @Async("ioTaskExecutor")
    public void sendNotificationFireAndForget(Long userId, String message) {
        // If this throws, exception is lost unless you have AsyncUncaughtExceptionHandler
        System.out.println("Sending notification to user " + userId + ": " + message);
        // throw new RuntimeException("This exception is SILENTLY SWALLOWED!");
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ❌ ANTI-PATTERN: Internal @Async call (doesn't work!)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * THIS DOES NOT WORK! @Async only works through Spring proxy.
     * Internal method calls bypass the proxy and run synchronously.
     */
    public User findUserWrongInternalCall(Long userId) {
        // This calls the method directly, NOT through Spring proxy
        // The @Async annotation is completely ignored!
        CompletableFuture<User> future = findUserCorrect(userId);
        return future.join(); // This runs synchronously, defeating the purpose
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ✅ WORKAROUND: Self-injection for internal async calls
     * ─────────────────────────────────────────────────────────────────────────
     *
     * If you MUST call @Async method internally, inject the service into itself.
     * This is ugly but works. Better to avoid @Async for such cases.
     */
    // @Autowired
    // private UserServiceWithAsync self; // Self-injection
    //
    // public User findUserWithSelfInjection(Long userId) {
    //     // Call through the proxy via self-injection
    //     CompletableFuture<User> future = self.findUserCorrect(userId);
    //     return future.join();
    // }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * ❌ ANTI-PATTERN: @Async on private method (doesn't work!)
     * ─────────────────────────────────────────────────────────────────────────
     */
    // @Async("ioTaskExecutor")
    // private CompletableFuture<User> privateAsyncMethod(Long userId) {
    //     // THIS DOES NOT WORK! @Async requires public method
    //     return CompletableFuture.completedFuture(externalUserClient.fetchUser(userId));
    // }
}
