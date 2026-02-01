package com.mortitech.completablefuture.level1_basics;

import com.mortitech.completablefuture.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Level 1 - Basic CompletableFuture operations.
 * These tests demonstrate how to verify async behavior.
 */
class Level1_BasicExamplesTest {

    private Level1_BasicExamples examples;

    @BeforeEach
    void setUp() {
        examples = new Level1_BasicExamples();
    }

    @Test
    @DisplayName("supplyAsync returns a future that completes with user data")
    void testFetchUserAsync() {
        // When: we request a user asynchronously
        CompletableFuture<User> future = examples.fetchUserAsync(1L);

        // Then: the future should not be null
        assertNotNull(future);

        // And: when we wait for it (using join for cleaner test code)
        User user = future.join();

        // Then: we get the expected user
        assertEquals("john_doe", user.username());
        assertEquals("john@example.com", user.email());
    }

    @Test
    @DisplayName("Non-blocking approach allows chaining without blocking")
    void testNonBlockingApproach() {
        // Given: a holder for the async result
        AtomicReference<String> emailHolder = new AtomicReference<>();

        // When: we chain operations without blocking
        CompletableFuture<Void> future = examples.fetchUserNonBlocking_GOOD(1L)
            .thenApply(User::email)
            .thenAccept(emailHolder::set);

        // Then: initially the email might not be set (async!)
        // We need to wait for completion to verify
        future.join();

        // And: now the email should be set
        assertEquals("john@example.com", emailHolder.get());
    }

    @Test
    @DisplayName("thenApply transforms the result correctly")
    void testFetchUserEmail() {
        // When: we fetch just the email
        String email = examples.fetchUserEmail(1L).join();

        // Then: we get only the email, not the full user
        assertEquals("john@example.com", email);
    }

    @Test
    @DisplayName("completedFuture returns cached value immediately")
    void testCachedUser() {
        // Given: a cached user
        User cachedUser = User.of(99L, "cached_user", "cached@example.com", "VIP");

        // When: we fetch with cache
        CompletableFuture<User> future = examples.fetchUserWithCache(1L, cachedUser);

        // Then: future should already be completed (no async operation)
        assertTrue(future.isDone());

        // And: should return the cached user, not fetch a new one
        User result = future.join();
        assertEquals("cached_user", result.username());
    }

    @Test
    @DisplayName("Without cache, async fetch is performed")
    void testNoCachedUser() {
        // When: we fetch without cache (null)
        CompletableFuture<User> future = examples.fetchUserWithCache(1L, null);

        // Then: should perform async fetch
        User result = future.join();
        assertEquals("john_doe", result.username()); // From database
    }

    @Test
    @DisplayName("join() blocks and returns the result")
    void testJoin() {
        // When: we use join to block
        User user = examples.fetchUserWithJoin(1L);

        // Then: we get the user directly (no future wrapper)
        assertNotNull(user);
        assertEquals("john_doe", user.username());
    }

    @Test
    @DisplayName("Future completes on a different thread than the caller")
    void testAsyncExecutesOnDifferentThread() {
        // Given: the current thread name
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> executorThread = new AtomicReference<>();

        // When: we execute async operation
        examples.fetchUserAsync(1L)
            .thenAccept(user -> executorThread.set(Thread.currentThread().getName()))
            .join();

        // Then: the executor thread should be different (from ForkJoinPool)
        assertNotEquals(callerThread, executorThread.get());
        assertTrue(executorThread.get().contains("ForkJoinPool"));
    }
}
