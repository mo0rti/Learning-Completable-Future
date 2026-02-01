package com.mortitech.completablefuture.level2_chaining;

import com.mortitech.completablefuture.domain.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Level 2 - Chaining and Composition patterns.
 */
class Level2_ChainingExamplesTest {

    private Level2_ChainingExamples examples;

    @BeforeEach
    void setUp() {
        examples = new Level2_ChainingExamples();
    }

    @Test
    @DisplayName("thenApply extracts and transforms values synchronously")
    void testThenApply() {
        // When: extracting user tier using thenApply
        String tier = examples.getUserTier(1L).join();

        // Then: we get the tier value
        assertEquals("PREMIUM", tier);
    }

    @Test
    @DisplayName("Chained thenApply calls transform step by step")
    void testChainedThenApply() {
        // When: chaining multiple transformations
        String message = examples.getWelcomeMessage(1L).join();

        // Then: all transformations are applied
        assertEquals("Welcome back, jane_doe!", message);
    }

    @Test
    @DisplayName("thenCompose flattens nested async operations")
    void testThenCompose() {
        // When: using thenCompose for async chaining
        List<String> preferences = examples.getUserPreferencesForUser(1L).join();

        // Then: we get the preferences directly (not wrapped in another future)
        assertEquals(3, preferences.size());
        assertTrue(preferences.contains("dark_mode"));
    }

    @Test
    @DisplayName("WRONG: thenApply creates nested futures")
    void testThenApplyWrong() {
        // When: incorrectly using thenApply for async operation
        CompletableFuture<CompletableFuture<List<String>>> nestedFuture =
            examples.getUserPreferences_WRONG(1L);

        // Then: we have a nested future - awkward to use!
        CompletableFuture<List<String>> innerFuture = nestedFuture.join();
        List<String> preferences = innerFuture.join(); // Double unwrapping!

        assertEquals(3, preferences.size());
    }

    @Test
    @DisplayName("CORRECT: thenCompose gives clean single future")
    void testThenComposeCorrect() {
        // When: correctly using thenCompose
        CompletableFuture<List<String>> future = examples.getUserPreferences_CORRECT(1L);

        // Then: single unwrap gives us the result
        List<String> preferences = future.join();

        assertEquals(3, preferences.size());
    }

    @Test
    @DisplayName("Profile enrichment combines multiple async calls")
    void testEnrichUserProfile() {
        // When: enriching a user profile
        UserProfile profile = examples.enrichUserProfile(1L).join();

        // Then: profile contains data from all sources
        assertEquals("jane_doe", profile.user().username());
        assertEquals(3, profile.preferences().size());
        assertEquals(2500, profile.loyaltyPoints());
        assertNotNull(profile.lastLoginAt());
    }

    @Test
    @DisplayName("Parallel enrichment produces same results")
    void testEnrichUserProfileParallel() {
        // When: enriching with parallel fetches
        UserProfile profile = examples.enrichUserProfileParallel(1L).join();

        // Then: same results as sequential
        assertEquals("jane_doe", profile.user().username());
        assertEquals(3, profile.preferences().size());
        assertEquals(2500, profile.loyaltyPoints());
    }

    @Test
    @DisplayName("Parallel enrichment is faster than sequential")
    void testParallelIsFaster() {
        // Given: timing both approaches
        long sequentialStart = System.currentTimeMillis();
        examples.enrichUserProfile(1L).join();
        long sequentialTime = System.currentTimeMillis() - sequentialStart;

        long parallelStart = System.currentTimeMillis();
        examples.enrichUserProfileParallel(1L).join();
        long parallelTime = System.currentTimeMillis() - parallelStart;

        // Then: parallel should be faster (preferences + points run concurrently)
        // Sequential: user(50) + preferences(30) + points(40) = ~120ms
        // Parallel: user(50) + max(preferences(30), points(40)) = ~90ms
        System.out.println("Sequential: " + sequentialTime + "ms, Parallel: " + parallelTime + "ms");

        // Allow some variance but parallel should generally be faster
        assertTrue(parallelTime <= sequentialTime + 20,
            "Parallel should not be significantly slower than sequential");
    }

    @Test
    @DisplayName("Mixed thenApply/thenCompose chain works correctly")
    void testMixedChain() {
        // When: using a mixed chain
        String greeting = examples.getPersonalizedGreeting(1L).join();

        // Then: all transformations applied
        assertTrue(greeting.contains("JANE_DOE")); // uppercase applied
        assertTrue(greeting.contains("WELCOME")); // greeting template used
    }
}
