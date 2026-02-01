package com.mortitech.completablefuture.level5_spring.service;

import com.mortitech.completablefuture.domain.User;
import com.mortitech.completablefuture.domain.UserProfile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 5 — SPRING BOOT INTEGRATION: Service Layer Best Practices
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * BEST PRACTICES DEMONSTRATED:
 * 1. Inject executor instead of using commonPool
 * 2. Use @Qualifier to select specific executor
 * 3. Return CompletableFuture from service methods (don't block internally)
 * 4. Handle errors at appropriate level
 * 5. Use meaningful method names that indicate async behavior
 *
 * IMPORTANT: This class does NOT use @Async annotation.
 * We manually manage CompletableFuture for better control.
 * See UserServiceWithAsync for the @Async approach and its pitfalls.
 */
@Service
public class UserService {

    private final Executor ioExecutor;
    private final ExternalUserClient externalUserClient;
    private final PreferenceService preferenceService;
    private final LoyaltyService loyaltyService;

    // Constructor injection with @Qualifier to select specific executor
    public UserService(
            @Qualifier("ioTaskExecutor") Executor ioExecutor,
            ExternalUserClient externalUserClient,
            PreferenceService preferenceService,
            LoyaltyService loyaltyService) {
        this.ioExecutor = ioExecutor;
        this.externalUserClient = externalUserClient;
        this.preferenceService = preferenceService;
        this.loyaltyService = loyaltyService;
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 1: Simple async operation with injected executor
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Use the injected executor, not ForkJoinPool.commonPool().
     * This gives you control and visibility over thread usage.
     */
    public CompletableFuture<User> findUserAsync(Long userId) {
        return CompletableFuture.supplyAsync(
            () -> externalUserClient.fetchUser(userId),
            ioExecutor  // Using injected executor
        );
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 2: Parallel fetching with proper executor
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Launch independent operations in parallel using the same executor.
     * All I/O operations share the I/O executor pool.
     */
    public CompletableFuture<UserProfile> getUserProfileAsync(Long userId) {
        // All three operations start in parallel
        CompletableFuture<User> userFuture = findUserAsync(userId);

        CompletableFuture<List<String>> preferencesFuture =
            CompletableFuture.supplyAsync(
                () -> preferenceService.getPreferences(userId),
                ioExecutor
            );

        CompletableFuture<Integer> pointsFuture =
            CompletableFuture.supplyAsync(
                () -> loyaltyService.getPoints(userId),
                ioExecutor
            );

        // Combine all results when ready
        return CompletableFuture.allOf(userFuture, preferencesFuture, pointsFuture)
            .thenApplyAsync(ignored -> {
                User user = userFuture.join();
                List<String> preferences = preferencesFuture.join();
                int points = pointsFuture.join();

                return UserProfile.of(
                    user,
                    preferences,
                    LocalDateTime.now(),
                    points,
                    calculateRecommendedPlan(user.tier(), points)
                );
            }, ioExecutor);  // Continue on same executor
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 3: Error handling in service layer
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Handle errors at the service layer when you want to provide fallbacks.
     * Otherwise, let exceptions propagate to the controller.
     */
    public CompletableFuture<User> findUserWithFallbackAsync(Long userId) {
        return findUserAsync(userId)
            .exceptionallyAsync(throwable -> {
                // Log the error (use proper logging in production)
                System.err.println("Failed to fetch user " + userId + ": " + throwable.getMessage());
                // Return fallback
                return User.empty();
            }, ioExecutor);
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * PATTERN 4: Chaining async operations
     * ─────────────────────────────────────────────────────────────────────────
     *
     * When one operation depends on another, use thenComposeAsync.
     * Always pass the executor to maintain thread control.
     */
    public CompletableFuture<String> getUserRecommendationAsync(Long userId) {
        return findUserAsync(userId)
            .thenComposeAsync(user ->
                CompletableFuture.supplyAsync(
                    () -> loyaltyService.getRecommendation(user),
                    ioExecutor
                ),
                ioExecutor
            );
    }

    private String calculateRecommendedPlan(String currentTier, int loyaltyPoints) {
        if (loyaltyPoints > 5000) return "VIP";
        if (loyaltyPoints > 2000 && "BASIC".equals(currentTier)) return "PREMIUM";
        return currentTier;
    }
}
