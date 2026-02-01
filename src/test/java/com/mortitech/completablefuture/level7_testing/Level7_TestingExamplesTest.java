package com.mortitech.completablefuture.level7_testing;

import com.mortitech.completablefuture.domain.User;
import com.mortitech.completablefuture.domain.UserProfile;
import com.mortitech.completablefuture.level7_testing.UserServiceForTesting.LoyaltyClient;
import com.mortitech.completablefuture.level7_testing.UserServiceForTesting.PreferenceClient;
import com.mortitech.completablefuture.level7_testing.UserServiceForTesting.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 7 — TESTING COMPLETABLEFUTURE: Comprehensive Testing Guide
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * TESTING STRATEGIES COVERED:
 * 1. Using completedFuture() for mocking async responses
 * 2. Using failedFuture() for mocking async errors
 * 3. Synchronous executor for deterministic tests
 * 4. Testing timeouts
 * 5. Testing error handling paths
 * 6. Testing composed async operations
 * 7. Verifying async behavior
 */
@ExtendWith(MockitoExtension.class)
class Level7_TestingExamplesTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PreferenceClient preferenceClient;

    @Mock
    private LoyaltyClient loyaltyClient;

    private UserServiceForTesting service;

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * KEY TESTING PATTERN: Use Synchronous Executor
     * ─────────────────────────────────────────────────────────────────────────
     *
     * For unit tests, use a synchronous (direct) executor that runs
     * tasks on the calling thread. This makes tests:
     * - Deterministic (no race conditions)
     * - Fast (no thread scheduling)
     * - Easy to debug (single thread)
     */
    private final Executor synchronousExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        service = new UserServiceForTesting(
            userRepository,
            preferenceClient,
            loyaltyClient,
            synchronousExecutor  // Synchronous for deterministic tests
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 1: BASIC ASYNC TESTING
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Basic Async Operations")
    class BasicAsyncTests {

        @Test
        @DisplayName("Test successful async operation with join()")
        void testSuccessfulAsync() {
            // Given: mock returns a user
            User expectedUser = User.of(1L, "test_user", "test@example.com", "PREMIUM");
            when(userRepository.findById(1L)).thenReturn(expectedUser);

            // When: calling async method
            CompletableFuture<User> future = service.findUserById(1L);

            // Then: use join() to get result (blocks until complete)
            User result = future.join();
            assertEquals("test_user", result.username());

            // Verify the repository was called
            verify(userRepository).findById(1L);
        }

        @Test
        @DisplayName("Test with completedFuture - mock returning pre-completed future")
        void testWithCompletedFuture() {
            // completedFuture() creates an already-completed future
            // Useful when mocking methods that return CompletableFuture
            User user = User.of(1L, "completed_user", "completed@example.com", "VIP");
            CompletableFuture<User> preCompleted = CompletableFuture.completedFuture(user);

            // The future is already done
            assertTrue(preCompleted.isDone());
            assertEquals("completed_user", preCompleted.join().username());
        }

        @Test
        @DisplayName("Test with failedFuture - mock returning failed future")
        void testWithFailedFuture() {
            // failedFuture() creates a future that's already failed
            RuntimeException error = new RuntimeException("Database connection failed");
            CompletableFuture<User> preFailed = CompletableFuture.failedFuture(error);

            // The future is done but completed exceptionally
            assertTrue(preFailed.isDone());
            assertTrue(preFailed.isCompletedExceptionally());

            // Attempting to join() throws CompletionException
            CompletionException thrown = assertThrows(
                CompletionException.class,
                preFailed::join
            );
            assertEquals("Database connection failed", thrown.getCause().getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 2: TESTING ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Test exceptionally() returns fallback on error")
        void testFallbackOnError() {
            // Given: repository throws exception
            when(userRepository.findById(anyLong()))
                .thenThrow(new RuntimeException("DB Error"));

            // When: calling method with fallback
            User result = service.findUserWithFallback(1L).join();

            // Then: fallback user is returned (not exception thrown)
            assertEquals("unknown", result.username());
            assertEquals(0L, result.id());
        }

        @Test
        @DisplayName("Test exception propagation when no fallback")
        void testExceptionPropagation() {
            // Given: repository throws exception
            when(userRepository.findById(anyLong()))
                .thenThrow(new RuntimeException("DB Error"));

            // When/Then: exception propagates wrapped in CompletionException
            CompletableFuture<User> future = service.findUserById(1L);

            CompletionException thrown = assertThrows(
                CompletionException.class,
                future::join
            );

            // Unwrap to get actual cause
            assertEquals("DB Error", thrown.getCause().getMessage());
        }

        @Test
        @DisplayName("Test partial failure in parallel operations")
        void testPartialFailure() {
            // Given: user succeeds, preferences fail, points succeed
            when(userRepository.findById(1L))
                .thenReturn(User.of(1L, "partial", "partial@example.com", "BASIC"));
            when(preferenceClient.getPreferences(1L))
                .thenThrow(new RuntimeException("Preferences service down"));
            when(loyaltyClient.getPoints(1L))
                .thenReturn(1000);

            // When: getting profile (all three are fetched in parallel)
            CompletableFuture<UserProfile> future = service.getUserProfile(1L);

            // Then: the whole operation fails because one part failed
            assertThrows(CompletionException.class, future::join);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 3: TESTING TIMEOUTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Timeout Tests")
    class TimeoutTests {

        @Test
        @DisplayName("Test operation completes before timeout")
        void testCompletesBeforeTimeout() {
            // Given: fast repository
            when(userRepository.findById(1L))
                .thenReturn(User.of(1L, "fast_user", "fast@example.com", "BASIC"));

            // When: calling with generous timeout
            User result = service.findUserWithTimeout(1L, 5000).join();

            // Then: succeeds
            assertEquals("fast_user", result.username());
        }

        @Test
        @DisplayName("Test timeout throws TimeoutException")
        void testTimeoutException() {
            // For timeout testing, we need an async executor that actually delays
            Executor asyncExecutor = Executors.newSingleThreadExecutor();
            UserServiceForTesting asyncService = new UserServiceForTesting(
                userRepository, preferenceClient, loyaltyClient, asyncExecutor
            );

            // Given: slow repository (simulated)
            when(userRepository.findById(anyLong())).thenAnswer(invocation -> {
                Thread.sleep(500); // Slower than timeout
                return User.of(1L, "slow_user", "slow@example.com", "BASIC");
            });

            // When: calling with short timeout
            CompletableFuture<User> future = asyncService.findUserWithTimeout(1L, 50);

            // Then: TimeoutException is thrown
            CompletionException thrown = assertThrows(
                CompletionException.class,
                future::join
            );
            assertInstanceOf(TimeoutException.class, thrown.getCause());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 4: TESTING COMPOSED OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Composed Operations Tests")
    class ComposedOperationsTests {

        @Test
        @DisplayName("Test all parallel fetches combine correctly")
        void testParallelFetches() {
            // Given: all services return data
            User user = User.of(1L, "composed_user", "composed@example.com", "BASIC");
            List<String> prefs = List.of("pref1", "pref2");
            int points = 3000;

            when(userRepository.findById(1L)).thenReturn(user);
            when(preferenceClient.getPreferences(1L)).thenReturn(prefs);
            when(loyaltyClient.getPoints(1L)).thenReturn(points);

            // When: getting full profile
            UserProfile profile = service.getUserProfile(1L).join();

            // Then: all data is combined
            assertEquals("composed_user", profile.user().username());
            assertEquals(2, profile.preferences().size());
            assertEquals(3000, profile.loyaltyPoints());
            assertEquals("PREMIUM", profile.recommendedPlan()); // 3000 points, BASIC tier -> PREMIUM

            // Verify all services were called
            verify(userRepository).findById(1L);
            verify(preferenceClient).getPreferences(1L);
            verify(loyaltyClient).getPoints(1L);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 5: TESTING PURE FUNCTIONS (NO ASYNC)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pure Function Tests (No Async)")
    class PureFunctionTests {

        /**
         * Test business logic separately from async mechanics.
         * These tests are fast, simple, and don't need any mocking.
         */

        @Test
        @DisplayName("Calculate plan upgrade for high points basic user")
        void testPlanUpgradeLogic() {
            // Pure function test - no async involved
            String result = service.calculatePlan("BASIC", 3000);
            assertEquals("PREMIUM", result);
        }

        @Test
        @DisplayName("VIP plan for very high points")
        void testVipPlan() {
            String result = service.calculatePlan("BASIC", 6000);
            assertEquals("VIP", result);
        }

        @Test
        @DisplayName("Keep current plan for low points")
        void testKeepCurrentPlan() {
            String result = service.calculatePlan("BASIC", 500);
            assertEquals("BASIC", result);
        }

        @Test
        @DisplayName("Build profile combines data correctly")
        void testBuildProfile() {
            User user = User.of(1L, "builder", "builder@example.com", "PREMIUM");
            List<String> prefs = List.of("dark_mode");
            int points = 1000;

            UserProfile profile = service.buildProfile(user, prefs, points);

            assertEquals("builder", profile.user().username());
            assertEquals(1, profile.preferences().size());
            assertEquals(1000, profile.loyaltyPoints());
            assertEquals("PREMIUM", profile.recommendedPlan()); // Keeps PREMIUM
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SECTION 6: ADVANCED TESTING PATTERNS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Advanced Testing Patterns")
    class AdvancedPatterns {

        @Test
        @DisplayName("Test async behavior verification - verify calls happened")
        void testAsyncCallsVerification() {
            // Given
            when(userRepository.findById(1L))
                .thenReturn(User.of(1L, "verify", "verify@example.com", "BASIC"));

            // When
            service.findUserById(1L).join();

            // Then - verify interaction
            verify(userRepository, times(1)).findById(1L);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        @DisplayName("Test future state inspection")
        void testFutureStateInspection() {
            // Given
            when(userRepository.findById(1L))
                .thenReturn(User.of(1L, "state", "state@example.com", "BASIC"));

            // When
            CompletableFuture<User> future = service.findUserById(1L);

            // With synchronous executor, future is already complete
            assertTrue(future.isDone(), "Future should be completed");
            assertFalse(future.isCancelled(), "Future should not be cancelled");
            assertFalse(future.isCompletedExceptionally(), "Future should not have failed");
        }

        @Test
        @DisplayName("Test with real async executor - verify eventually completes")
        void testRealAsyncBehavior() throws Exception {
            // Use real async executor
            Executor asyncExecutor = Executors.newSingleThreadExecutor();
            UserServiceForTesting asyncService = new UserServiceForTesting(
                userRepository, preferenceClient, loyaltyClient, asyncExecutor
            );

            when(userRepository.findById(1L))
                .thenReturn(User.of(1L, "async", "async@example.com", "BASIC"));

            // When
            CompletableFuture<User> future = asyncService.findUserById(1L);

            // Then - wait for completion with timeout
            User result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("async", result.username());
        }

        @Test
        @DisplayName("Test that operations run in parallel (not sequential)")
        void testParallelExecution() {
            // Use real async executor with multiple threads
            Executor parallelExecutor = Executors.newFixedThreadPool(3);
            UserServiceForTesting parallelService = new UserServiceForTesting(
                userRepository, preferenceClient, loyaltyClient, parallelExecutor
            );

            // Given: each service takes 100ms
            when(userRepository.findById(1L)).thenAnswer(inv -> {
                Thread.sleep(100);
                return User.of(1L, "parallel", "parallel@example.com", "BASIC");
            });
            when(preferenceClient.getPreferences(1L)).thenAnswer(inv -> {
                Thread.sleep(100);
                return List.of("pref1");
            });
            when(loyaltyClient.getPoints(1L)).thenAnswer(inv -> {
                Thread.sleep(100);
                return 1000;
            });

            // When: fetching profile
            long start = System.currentTimeMillis();
            UserProfile profile = parallelService.getUserProfile(1L).join();
            long duration = System.currentTimeMillis() - start;

            // Then: should complete in ~100ms (parallel), not ~300ms (sequential)
            System.out.println("Parallel execution took: " + duration + "ms");
            assertTrue(duration < 250,
                "Should run in parallel (~100ms), but took " + duration + "ms");

            assertEquals("parallel", profile.user().username());
        }
    }
}
