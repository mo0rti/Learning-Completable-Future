package com.mortitech.completablefuture.level6_advanced;

import com.mortitech.completablefuture.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Level 6 - Advanced Patterns.
 */
class Level6_AdvancedPatternsTest {

    private Level6_AdvancedPatterns patterns;

    @BeforeEach
    void setUp() {
        patterns = new Level6_AdvancedPatterns();
    }

    @AfterEach
    void tearDown() {
        patterns.shutdown();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TIMEOUT TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("orTimeout throws TimeoutException when operation is too slow")
    void testOrTimeout() {
        // When: operation takes longer than timeout
        var future = patterns.fetchUserWithTimeout(1L, Duration.ofMillis(50));

        // Then: TimeoutException is thrown
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(TimeoutException.class, ex.getCause());
    }

    @Test
    @DisplayName("completeOnTimeout returns fallback value")
    void testCompleteOnTimeout() {
        // When: operation times out with fallback
        User user = patterns.fetchUserWithFallbackTimeout(1L, Duration.ofMillis(50)).join();

        // Then: fallback (empty) user is returned
        assertEquals("unknown", user.username());
    }

    @Test
    @DisplayName("Manual timeout works like orTimeout")
    void testManualTimeout() {
        // When: using manual timeout
        var future = patterns.fetchUserWithManualTimeout(1L, Duration.ofMillis(50));

        // Then: TimeoutException is thrown
        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(TimeoutException.class, ex.getCause());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RETRY TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Retry succeeds after failures")
    void testRetry() {
        // When: operation fails initially but eventually succeeds
        User user = patterns.fetchUserWithRetry(1L, 3).join();

        // Then: we get the user (after retries)
        assertEquals("recovered_user", user.username());
    }

    @Test
    @DisplayName("Exponential backoff succeeds after failures")
    void testExponentialBackoff() {
        // When: using exponential backoff retry
        long start = System.currentTimeMillis();
        User user = patterns.fetchUserWithExponentialBackoff(1L).join();
        long duration = System.currentTimeMillis() - start;

        // Then: we get the user
        assertEquals("recovered_user", user.username());

        // And: there was some backoff delay (100ms + 200ms minimum for 2 retries)
        System.out.println("Exponential backoff took: " + duration + "ms");
        assertTrue(duration > 250, "Should have backoff delays");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RATE LIMITING TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Batch processing respects concurrency limit")
    void testConcurrencyLimit() {
        // Given: a list of user IDs
        List<Long> userIds = List.of(1L, 2L, 3L, 4L, 5L);

        // When: fetching with concurrency limit of 2
        List<User> users = patterns.fetchUsersWithConcurrencyLimit(userIds, 2).join();

        // Then: all users are fetched
        assertEquals(5, users.size());

        // And: they're the correct users
        assertTrue(users.stream().allMatch(u -> u.username().startsWith("slow_user")));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ORCHESTRATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Hedged requests return first successful result")
    void testHedgedRequests() {
        // When: using hedged requests
        User user = patterns.fetchUserHedged(1L).join();

        // Then: we get a user from one of the services
        assertNotNull(user);
        assertTrue(user.username().contains("_user"));
    }

    @Test
    @DisplayName("Fan-out fetches all related items")
    void testFanOut() {
        // When: fetching user and friends
        List<User> friends = patterns.fetchUserAndFriends(1L).join();

        // Then: we get all friends
        assertEquals(3, friends.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // THREAD SAFETY TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Non-blocking pattern avoids deadlock")
    void testNoDeadlock() {
        // When: using non-blocking pattern
        String result = patterns.noDeadlockExample_SAFE().join();

        // Then: completes successfully
        assertEquals("outer: inner", result);
    }

    @Test
    @DisplayName("Virtual threads handle blocking safely")
    void testVirtualThreads() {
        // When: using virtual threads with blocking
        String result = patterns.virtualThreadExample_SAFE().join();

        // Then: completes successfully
        assertEquals("outer: inner result", result);
    }
}
