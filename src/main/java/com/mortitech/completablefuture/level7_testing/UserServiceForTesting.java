package com.mortitech.completablefuture.level7_testing;

import com.mortitech.completablefuture.domain.User;
import com.mortitech.completablefuture.domain.UserProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 7 — TESTING: Service Class Designed for Testability
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * KEY TESTABILITY PRINCIPLES:
 * 1. Inject dependencies (including executor) - enables mocking
 * 2. Use interfaces for external services - easy to mock
 * 3. Keep async logic separate from business logic
 * 4. Design methods to be deterministic when possible
 */
public class UserServiceForTesting {

    private final UserRepository userRepository;
    private final PreferenceClient preferenceClient;
    private final LoyaltyClient loyaltyClient;
    private final Executor executor;

    public UserServiceForTesting(
            UserRepository userRepository,
            PreferenceClient preferenceClient,
            LoyaltyClient loyaltyClient,
            Executor executor) {
        this.userRepository = userRepository;
        this.preferenceClient = preferenceClient;
        this.loyaltyClient = loyaltyClient;
        this.executor = executor;
    }

    /**
     * Async method that's easy to test because:
     * - Repository is injected and mockable
     * - Executor is injected (use synchronous executor in tests)
     */
    public CompletableFuture<User> findUserById(Long userId) {
        return CompletableFuture.supplyAsync(
            () -> userRepository.findById(userId),
            executor
        );
    }

    /**
     * Composed async operations - test each part independently.
     */
    public CompletableFuture<UserProfile> getUserProfile(Long userId) {
        CompletableFuture<User> userFuture = findUserById(userId);
        CompletableFuture<List<String>> prefsFuture = getPreferencesAsync(userId);
        CompletableFuture<Integer> pointsFuture = getLoyaltyPointsAsync(userId);

        return CompletableFuture.allOf(userFuture, prefsFuture, pointsFuture)
            .thenApplyAsync(ignored ->
                buildProfile(
                    userFuture.join(),
                    prefsFuture.join(),
                    pointsFuture.join()
                ),
                executor
            );
    }

    /**
     * Method with error handling - test both success and failure paths.
     */
    public CompletableFuture<User> findUserWithFallback(Long userId) {
        return findUserById(userId)
            .exceptionally(ex -> {
                // Log would go here in production
                return User.empty();
            });
    }

    /**
     * Method with timeout - test both fast and slow scenarios.
     */
    public CompletableFuture<User> findUserWithTimeout(Long userId, long timeoutMs) {
        return findUserById(userId)
            .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // Internal async methods
    private CompletableFuture<List<String>> getPreferencesAsync(Long userId) {
        return CompletableFuture.supplyAsync(
            () -> preferenceClient.getPreferences(userId),
            executor
        );
    }

    private CompletableFuture<Integer> getLoyaltyPointsAsync(Long userId) {
        return CompletableFuture.supplyAsync(
            () -> loyaltyClient.getPoints(userId),
            executor
        );
    }

    // Pure function - easy to unit test independently
    UserProfile buildProfile(User user, List<String> prefs, int points) {
        String recommendedPlan = calculatePlan(user.tier(), points);
        return UserProfile.of(user, prefs, LocalDateTime.now(), points, recommendedPlan);
    }

    // Another pure function - test in isolation
    String calculatePlan(String currentTier, int points) {
        if (points > 5000) return "VIP";
        if (points > 2000 && "BASIC".equals(currentTier)) return "PREMIUM";
        return currentTier;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERFACES FOR MOCKING
    // ─────────────────────────────────────────────────────────────────────────

    public interface UserRepository {
        User findById(Long id);
    }

    public interface PreferenceClient {
        List<String> getPreferences(Long userId);
    }

    public interface LoyaltyClient {
        int getPoints(Long userId);
    }
}
