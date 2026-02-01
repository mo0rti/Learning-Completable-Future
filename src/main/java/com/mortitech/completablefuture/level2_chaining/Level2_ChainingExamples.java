package com.mortitech.completablefuture.level2_chaining;

import com.mortitech.completablefuture.domain.User;
import com.mortitech.completablefuture.domain.UserProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 2 — CHAINING & COMPOSITION: thenApply vs thenCompose
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * THE CRITICAL DIFFERENCE:
 *
 * thenApply:  Takes a Function<T, U> → returns CompletableFuture<U>
 *             Use when your transformation is SYNCHRONOUS
 *
 * thenCompose: Takes a Function<T, CompletableFuture<U>> → returns CompletableFuture<U>
 *              Use when your transformation is ASYNCHRONOUS (returns another future)
 *
 * ANALOGY:
 * - thenApply is like Stream.map()
 * - thenCompose is like Stream.flatMap()
 *
 * COMMON MISTAKE: Using thenApply when you need thenCompose results in
 * CompletableFuture<CompletableFuture<T>> - a nested mess!
 */
public class Level2_ChainingExamples {

    // ─────────────────────────────────────────────────────────────────────────
    // SIMULATED ASYNC SERVICES (imagine these call external APIs/databases)
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<User> fetchUser(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            return User.of(userId, "jane_doe", "jane@example.com", "PREMIUM");
        });
    }

    public CompletableFuture<List<String>> fetchUserPreferences(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(30);
            return List.of("dark_mode", "email_notifications", "weekly_digest");
        });
    }

    public CompletableFuture<Integer> fetchLoyaltyPoints(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(40);
            return 2500;
        });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 1: thenApply - Synchronous Transformation
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Use thenApply when the transformation doesn't involve another async call.
     * The function runs synchronously after the previous stage completes.
     */
    public CompletableFuture<String> getUserTier(Long userId) {
        return fetchUser(userId)
            // thenApply: User -> String (synchronous extraction)
            .thenApply(user -> user.tier());
    }

    public CompletableFuture<String> getWelcomeMessage(Long userId) {
        return fetchUser(userId)
            // Chain multiple thenApply for sequential transformations
            .thenApply(user -> user.username())
            .thenApply(username -> "Welcome back, " + username + "!");
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 2: thenCompose - Asynchronous Chaining
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Use thenCompose when the next step IS ANOTHER async operation.
     * This "flattens" the nested futures into a single CompletableFuture.
     */
    public CompletableFuture<List<String>> getUserPreferencesForUser(Long userId) {
        return fetchUser(userId)
            // thenCompose: User -> CompletableFuture<List<String>>
            // Without thenCompose, we'd get CompletableFuture<CompletableFuture<List<String>>>!
            .thenCompose(user -> fetchUserPreferences(user.id()));
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 3: The Classic Mistake - Using thenApply Instead of thenCompose
     * ─────────────────────────────────────────────────────────────────────────
     */

    /**
     * ❌ WRONG: This creates nested futures - CompletableFuture<CompletableFuture<...>>
     *
     * The result is unusable without double unwrapping.
     */
    public CompletableFuture<CompletableFuture<List<String>>> getUserPreferences_WRONG(Long userId) {
        return fetchUser(userId)
            // thenApply returns the future AS-IS, wrapping it in another future
            .thenApply(user -> fetchUserPreferences(user.id()));
            // Result: CompletableFuture<CompletableFuture<List<String>>> - WRONG!
    }

    /**
     * ✅ CORRECT: Use thenCompose to flatten the futures
     */
    public CompletableFuture<List<String>> getUserPreferences_CORRECT(Long userId) {
        return fetchUser(userId)
            // thenCompose unwraps the inner future automatically
            .thenCompose(user -> fetchUserPreferences(user.id()));
            // Result: CompletableFuture<List<String>> - CORRECT!
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 4: Real-World Scenario - User Profile Enrichment
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Common pattern: Fetch user, then enrich with additional data.
     * This demonstrates chaining async calls in a realistic scenario.
     */
    public CompletableFuture<UserProfile> enrichUserProfile(Long userId) {
        return fetchUser(userId)
            .thenCompose(user ->
                // After getting user, fetch their preferences
                fetchUserPreferences(user.id())
                    .thenCompose(preferences ->
                        // Then fetch loyalty points
                        fetchLoyaltyPoints(user.id())
                            .thenApply(points ->
                                // Finally, combine everything into UserProfile
                                // This is synchronous - just object construction
                                UserProfile.of(
                                    user,
                                    preferences,
                                    LocalDateTime.now(),
                                    points,
                                    calculateRecommendedPlan(user.tier(), points)
                                )
                            )
                    )
            );
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 5: Better Pattern - Combining Independent Futures
     * ─────────────────────────────────────────────────────────────────────────
     *
     * The nested approach above works but is sequential (slower).
     * If preferences and points don't depend on each other, run them in parallel!
     * We'll explore this more in Level 3.
     */
    public CompletableFuture<UserProfile> enrichUserProfileParallel(Long userId) {
        return fetchUser(userId)
            .thenCompose(user -> {
                // Launch BOTH fetches in parallel - they don't depend on each other
                CompletableFuture<List<String>> preferencesFuture = fetchUserPreferences(user.id());
                CompletableFuture<Integer> pointsFuture = fetchLoyaltyPoints(user.id());

                // thenCombine waits for both and combines results
                return preferencesFuture.thenCombine(pointsFuture, (preferences, points) ->
                    UserProfile.of(
                        user,
                        preferences,
                        LocalDateTime.now(),
                        points,
                        calculateRecommendedPlan(user.tier(), points)
                    )
                );
            });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 6: Mixing thenApply and thenCompose in a Chain
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Real code often mixes both - just pick the right one for each step.
     */
    public CompletableFuture<String> getPersonalizedGreeting(Long userId) {
        return fetchUser(userId)
            .thenApply(user -> user.username())          // sync: extract username
            .thenCompose(this::fetchGreetingTemplate)    // async: fetch template from service
            .thenApply(String::toUpperCase);             // sync: transform result
    }

    private CompletableFuture<String> fetchGreetingTemplate(String username) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(20);
            return "Hello, " + username + "! Welcome to our platform.";
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────

    private String calculateRecommendedPlan(String currentTier, int loyaltyPoints) {
        if (loyaltyPoints > 5000) return "VIP";
        if (loyaltyPoints > 2000 && "BASIC".equals(currentTier)) return "PREMIUM";
        return currentTier;
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
