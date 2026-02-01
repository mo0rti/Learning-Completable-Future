package com.mortitech.completablefuture.level5_spring;

import com.mortitech.completablefuture.domain.User;
import com.mortitech.completablefuture.domain.UserProfile;
import com.mortitech.completablefuture.level5_spring.service.ExternalUserClient;
import com.mortitech.completablefuture.level5_spring.service.LoyaltyService;
import com.mortitech.completablefuture.level5_spring.service.PreferenceService;
import com.mortitech.completablefuture.level5_spring.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Tests for Level 5 - Spring Boot Integration.
 *
 * TESTING ASYNC SERVICES:
 * 1. Mock dependencies to avoid real I/O
 * 2. Use synchronous executor for deterministic tests
 * 3. Or use real executor and join() for integration tests
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private ExternalUserClient externalUserClient;

    @Mock
    private PreferenceService preferenceService;

    @Mock
    private LoyaltyService loyaltyService;

    private UserService userService;

    // Use direct executor for synchronous, deterministic tests
    private final Executor testExecutor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        userService = new UserService(
            testExecutor,
            externalUserClient,
            preferenceService,
            loyaltyService
        );
    }

    @Test
    @DisplayName("findUserAsync returns user from external client")
    void testFindUserAsync() {
        // Given
        User expectedUser = User.of(1L, "test_user", "test@example.com", "PREMIUM");
        when(externalUserClient.fetchUser(1L)).thenReturn(expectedUser);

        // When
        User result = userService.findUserAsync(1L).join();

        // Then
        assertEquals("test_user", result.username());
        assertEquals("PREMIUM", result.tier());
    }

    @Test
    @DisplayName("getUserProfileAsync combines data from multiple services")
    void testGetUserProfileAsync() {
        // Given
        User user = User.of(1L, "profile_user", "profile@example.com", "BASIC");
        List<String> preferences = List.of("pref1", "pref2");
        int loyaltyPoints = 3000;

        when(externalUserClient.fetchUser(1L)).thenReturn(user);
        when(preferenceService.getPreferences(1L)).thenReturn(preferences);
        when(loyaltyService.getPoints(1L)).thenReturn(loyaltyPoints);

        // When
        UserProfile profile = userService.getUserProfileAsync(1L).join();

        // Then
        assertEquals("profile_user", profile.user().username());
        assertEquals(2, profile.preferences().size());
        assertEquals(3000, profile.loyaltyPoints());
        // User was BASIC with 3000 points -> recommended PREMIUM
        assertEquals("PREMIUM", profile.recommendedPlan());
    }

    @Test
    @DisplayName("findUserWithFallbackAsync returns fallback on error")
    void testFindUserWithFallbackAsync() {
        // Given
        when(externalUserClient.fetchUser(anyLong()))
            .thenThrow(new RuntimeException("Service unavailable"));

        // When
        User result = userService.findUserWithFallbackAsync(1L).join();

        // Then - should return fallback user
        assertEquals("unknown", result.username());
        assertEquals(0L, result.id());
    }

    @Test
    @DisplayName("getUserRecommendationAsync chains operations correctly")
    void testGetUserRecommendationAsync() {
        // Given
        User user = User.of(1L, "rec_user", "rec@example.com", "VIP");
        when(externalUserClient.fetchUser(1L)).thenReturn(user);
        when(loyaltyService.getRecommendation(any(User.class)))
            .thenReturn("Exclusive VIP deals available!");

        // When
        String recommendation = userService.getUserRecommendationAsync(1L).join();

        // Then
        assertEquals("Exclusive VIP deals available!", recommendation);
    }

    @Test
    @DisplayName("Parallel profile fetch is faster than sequential")
    void testParallelPerformance() {
        // Given - each service takes time
        when(externalUserClient.fetchUser(anyLong())).thenAnswer(inv -> {
            Thread.sleep(50);
            return User.of(1L, "perf_user", "perf@example.com", "BASIC");
        });
        when(preferenceService.getPreferences(anyLong())).thenAnswer(inv -> {
            Thread.sleep(50);
            return List.of("pref1");
        });
        when(loyaltyService.getPoints(anyLong())).thenAnswer(inv -> {
            Thread.sleep(50);
            return 1000;
        });

        // Use real multi-threaded executor for this test
        Executor parallelExecutor = Executors.newFixedThreadPool(4);
        UserService parallelService = new UserService(
            parallelExecutor,
            externalUserClient,
            preferenceService,
            loyaltyService
        );

        // When
        long start = System.currentTimeMillis();
        parallelService.getUserProfileAsync(1L).join();
        long duration = System.currentTimeMillis() - start;

        // Then - should be closer to ~50ms (parallel) than ~150ms (sequential)
        // Allow some overhead
        System.out.println("Parallel fetch took: " + duration + "ms");
        assertTrue(duration < 120, "Parallel execution should be faster than sequential");
    }
}
