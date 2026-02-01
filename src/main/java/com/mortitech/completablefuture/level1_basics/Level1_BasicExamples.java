package com.mortitech.completablefuture.level1_basics;

import com.mortitech.completablefuture.domain.User;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 1 — BASICS: Understanding CompletableFuture Fundamentals
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHY CompletableFuture EXISTS:
 * - Traditional Future (Java 5) is limited: you can only block with get()
 * - CompletableFuture allows chaining, composition, and non-blocking callbacks
 * - Essential for building responsive, high-throughput applications
 *
 * WHEN TO USE:
 * ✅ I/O-bound operations (database calls, HTTP requests, file operations)
 * ✅ When you need to combine multiple independent operations
 * ✅ When you want non-blocking behavior with callbacks
 *
 * WHEN NOT TO USE:
 * ❌ CPU-bound operations (use parallel streams instead)
 * ❌ Simple synchronous operations (adds unnecessary complexity)
 * ❌ When you need strict ordering with no parallelism benefit
 */
public class Level1_BasicExamples {

    // Simulates a database call - the classic use case for async
    private User fetchUserFromDatabase(Long userId) {
        simulateLatency(100); // Simulating I/O latency
        return User.of(userId, "john_doe", "john@example.com", "PREMIUM");
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 1: supplyAsync - The Starting Point
     * ─────────────────────────────────────────────────────────────────────────
     *
     * supplyAsync runs a Supplier in the ForkJoinPool.commonPool() by default.
     * It returns immediately with a CompletableFuture, not blocking the caller.
     *
     * Use supplyAsync when you need to return a value.
     * Use runAsync when you don't need a return value (side effects only).
     */
    public CompletableFuture<User> fetchUserAsync(Long userId) {
        // This returns IMMEDIATELY - the actual work happens on another thread
        return CompletableFuture.supplyAsync(() -> {
            // This lambda runs on ForkJoinPool.commonPool() thread
            return fetchUserFromDatabase(userId);
        });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 2: Blocking vs Non-Blocking - Understanding the Difference
     * ─────────────────────────────────────────────────────────────────────────
     */

    /**
     * ❌ ANTI-PATTERN: Blocking call - defeats the purpose of async
     *
     * This blocks the calling thread until the result is available.
     * You might as well have called the database synchronously!
     */
    public User fetchUserBlocking_BAD(Long userId) {
        CompletableFuture<User> future = fetchUserAsync(userId);
        try {
            // .get() BLOCKS until the result is ready
            // This thread sits idle, wasting resources
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch user", e);
        }
    }

    /**
     * ✅ CORRECT: Non-blocking - let the caller decide what to do with the future
     *
     * Return the CompletableFuture and let the caller chain operations.
     * The caller can process the result when it arrives without blocking.
     */
    public CompletableFuture<User> fetchUserNonBlocking_GOOD(Long userId) {
        return fetchUserAsync(userId);
        // Caller can now chain: .thenApply(), .thenAccept(), etc.
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 3: thenAccept - Processing Results Without Blocking
     * ─────────────────────────────────────────────────────────────────────────
     *
     * thenAccept consumes the result without returning a new value.
     * Perfect for side effects like logging, caching, or sending notifications.
     */
    public CompletableFuture<Void> fetchAndLogUser(Long userId) {
        return fetchUserAsync(userId)
            .thenAccept(user -> {
                // This runs AFTER fetchUserAsync completes, on the same thread
                // that completed the previous stage (or caller thread if already done)
                System.out.println("Fetched user: " + user.username());
            });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 4: thenApply - Transforming Results
     * ─────────────────────────────────────────────────────────────────────────
     *
     * thenApply transforms the result and returns a new CompletableFuture.
     * Think of it like .map() on a Stream.
     */
    public CompletableFuture<String> fetchUserEmail(Long userId) {
        return fetchUserAsync(userId)
            .thenApply(user -> user.email()); // Transform User -> String
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 5: Creating Already-Completed Futures
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Useful for:
     * - Returning cached values
     * - Default/fallback values
     * - Testing (mock async operations)
     * - Early returns when validation fails
     */
    public CompletableFuture<User> fetchUserWithCache(Long userId, User cachedUser) {
        if (cachedUser != null) {
            // Return immediately without any async operation
            return CompletableFuture.completedFuture(cachedUser);
        }
        return fetchUserAsync(userId);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 6: join() vs get() - When Blocking is Necessary
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Sometimes you DO need to block (e.g., in tests, or at the edge of your app).
     * Prefer join() over get():
     * - join() throws unchecked CompletionException
     * - get() throws checked InterruptedException, ExecutionException
     */
    public User fetchUserWithJoin(Long userId) {
        // join() is cleaner when you must block
        // Still throws if the async operation failed, but as unchecked exception
        return fetchUserAsync(userId).join();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITY METHODS
    // ─────────────────────────────────────────────────────────────────────────

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
