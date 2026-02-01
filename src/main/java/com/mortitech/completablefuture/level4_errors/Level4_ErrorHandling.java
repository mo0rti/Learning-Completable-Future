package com.mortitech.completablefuture.level4_errors;

import com.mortitech.completablefuture.domain.User;

import java.util.concurrent.CompletableFuture;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 4 — ERROR HANDLING: Graceful Failure Management
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Error handling in CompletableFuture is CRITICAL for production systems.
 * Async errors are easy to swallow silently - leading to mysterious bugs.
 *
 * THREE MAIN ERROR HANDLING METHODS:
 *
 * 1. exceptionally(Function<Throwable, T>)
 *    - Handles exceptions and returns a fallback value
 *    - Like catch block that returns a default
 *    - Only called if there's an exception
 *
 * 2. handle(BiFunction<T, Throwable, U>)
 *    - Called for BOTH success and failure
 *    - You check which one happened and respond accordingly
 *    - More flexible than exceptionally
 *
 * 3. whenComplete(BiConsumer<T, Throwable>)
 *    - Like handle but doesn't transform the result
 *    - Used for side effects (logging, metrics, cleanup)
 *    - Doesn't swallow the exception
 *
 * GOLDEN RULE: Always handle errors at some point in your chain.
 * Unhandled exceptions in CompletableFuture can vanish silently!
 */
public class Level4_ErrorHandling {

    // ─────────────────────────────────────────────────────────────────────────
    // SIMULATED SERVICES THAT CAN FAIL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simulates a service that sometimes fails.
     */
    public CompletableFuture<User> fetchUserThatMayFail(Long userId, boolean shouldFail) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            if (shouldFail) {
                throw new UserNotFoundException("User not found: " + userId);
            }
            return User.of(userId, "bob", "bob@example.com", "BASIC");
        });
    }

    public CompletableFuture<User> fetchUserFromPrimaryService(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(100);
            throw new ServiceUnavailableException("Primary service is down");
        });
    }

    public CompletableFuture<User> fetchUserFromBackupService(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            return User.of(userId, "bob_backup", "bob@backup.com", "BASIC");
        });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 1: exceptionally - Return Fallback on Error
     * ─────────────────────────────────────────────────────────────────────────
     *
     * exceptionally is called ONLY when an exception occurs.
     * Use it to provide a default/fallback value.
     */
    public CompletableFuture<User> fetchUserWithFallback(Long userId) {
        return fetchUserThatMayFail(userId, true)
            .exceptionally(throwable -> {
                // Log the error (in production, use proper logging)
                System.err.println("Failed to fetch user: " + throwable.getMessage());
                // Return a fallback value
                return User.empty();
            });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 2: exceptionally with Specific Exception Types
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Check the exception type and handle differently.
     */
    public CompletableFuture<User> fetchUserWithTypedErrorHandling(Long userId, boolean shouldFail) {
        return fetchUserThatMayFail(userId, shouldFail)
            .exceptionally(throwable -> {
                // Unwrap CompletionException to get the actual cause
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

                if (cause instanceof UserNotFoundException) {
                    // Return empty user for "not found" - expected case
                    return User.empty();
                } else if (cause instanceof ServiceUnavailableException) {
                    // Log and rethrow for infrastructure issues
                    System.err.println("Service unavailable: " + cause.getMessage());
                    throw new RuntimeException("Service temporarily unavailable", cause);
                } else {
                    // Unknown error - rethrow
                    throw new RuntimeException("Unexpected error", cause);
                }
            });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 3: handle - Transform Both Success and Failure
     * ─────────────────────────────────────────────────────────────────────────
     *
     * handle() is called for BOTH success and failure cases.
     * More flexible than exceptionally when you need to transform in both cases.
     */
    public CompletableFuture<UserResult> fetchUserWithResultWrapper(Long userId, boolean shouldFail) {
        return fetchUserThatMayFail(userId, shouldFail)
            .handle((user, throwable) -> {
                if (throwable != null) {
                    // Failure case - wrap error in result
                    return UserResult.failure(throwable.getMessage());
                } else {
                    // Success case - wrap user in result
                    return UserResult.success(user);
                }
            });
    }

    /**
     * Result wrapper for representing success or failure.
     */
    public record UserResult(boolean success, User user, String errorMessage) {
        public static UserResult success(User user) {
            return new UserResult(true, user, null);
        }

        public static UserResult failure(String errorMessage) {
            return new UserResult(false, null, errorMessage);
        }
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 4: whenComplete - Side Effects Without Transformation
     * ─────────────────────────────────────────────────────────────────────────
     *
     * whenComplete is for side effects like logging, metrics, cleanup.
     * It does NOT transform the result or swallow exceptions.
     */
    public CompletableFuture<User> fetchUserWithLogging(Long userId, boolean shouldFail) {
        return fetchUserThatMayFail(userId, shouldFail)
            .whenComplete((user, throwable) -> {
                // This runs for BOTH success and failure
                if (throwable != null) {
                    System.err.println("ERROR fetching user " + userId + ": " + throwable.getMessage());
                    // In production: metrics.increment("user.fetch.error")
                } else {
                    System.out.println("Successfully fetched user: " + user.username());
                    // In production: metrics.increment("user.fetch.success")
                }
            });
        // The original result (or exception) is preserved and passed through
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 5: Fallback to Backup Service
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Common pattern: Try primary service, fall back to backup on failure.
     * Use exceptionallyCompose (Java 12+) for async fallback.
     */
    public CompletableFuture<User> fetchUserWithBackupFallback(Long userId) {
        return fetchUserFromPrimaryService(userId)
            // exceptionallyCompose allows async fallback (returns CompletableFuture)
            .exceptionallyCompose(throwable -> {
                System.err.println("Primary failed, trying backup: " + throwable.getMessage());
                return fetchUserFromBackupService(userId);
            });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 6: Partial Failure Handling in Parallel Operations
     * ─────────────────────────────────────────────────────────────────────────
     *
     * When running multiple operations in parallel, some may succeed and some
     * may fail. Handle each independently to not lose successful results.
     */
    public CompletableFuture<DashboardResult> loadDashboardWithPartialFailure(Long userId) {
        // Each future handles its own errors and returns a result wrapper
        CompletableFuture<DataResult<User>> userFuture =
            fetchUserThatMayFail(userId, false)
                .handle((user, ex) -> ex != null
                    ? DataResult.failure("user", ex.getMessage())
                    : DataResult.success("user", user));

        CompletableFuture<DataResult<String>> ordersFuture =
            fetchOrdersSummary(userId, true) // This one will fail
                .handle((summary, ex) -> ex != null
                    ? DataResult.failure("orders", ex.getMessage())
                    : DataResult.success("orders", summary));

        CompletableFuture<DataResult<String>> paymentsFuture =
            fetchPaymentsSummary(userId, false)
                .handle((summary, ex) -> ex != null
                    ? DataResult.failure("payments", ex.getMessage())
                    : DataResult.success("payments", summary));

        // Combine all results, including partial failures
        return CompletableFuture.allOf(userFuture, ordersFuture, paymentsFuture)
            .thenApply(ignored -> new DashboardResult(
                userFuture.join(),
                ordersFuture.join(),
                paymentsFuture.join()
            ));
    }

    public record DataResult<T>(String source, boolean success, T data, String error) {
        public static <T> DataResult<T> success(String source, T data) {
            return new DataResult<>(source, true, data, null);
        }

        public static <T> DataResult<T> failure(String source, String error) {
            return new DataResult<>(source, false, null, error);
        }
    }

    public record DashboardResult(
        DataResult<User> user,
        DataResult<String> orders,
        DataResult<String> payments
    ) {
        public boolean isFullySuccessful() {
            return user.success() && orders.success() && payments.success();
        }

        public boolean isPartiallySuccessful() {
            return user.success() || orders.success() || payments.success();
        }
    }

    private CompletableFuture<String> fetchOrdersSummary(Long userId, boolean shouldFail) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            if (shouldFail) throw new ServiceUnavailableException("Orders service down");
            return "5 orders, $1,500 total";
        });
    }

    private CompletableFuture<String> fetchPaymentsSummary(Long userId, boolean shouldFail) {
        return CompletableFuture.supplyAsync(() -> {
            simulateLatency(50);
            if (shouldFail) throw new ServiceUnavailableException("Payments service down");
            return "3 successful payments";
        });
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * EXAMPLE 7: Chain of Error Handlers
     * ─────────────────────────────────────────────────────────────────────────
     *
     * You can chain multiple error handlers for different concerns.
     */
    public CompletableFuture<User> fetchUserWithChainedErrorHandling(Long userId) {
        return fetchUserThatMayFail(userId, true)
            // First: Log the error (side effect only)
            .whenComplete((user, ex) -> {
                if (ex != null) {
                    System.err.println("Logging error: " + ex.getMessage());
                }
            })
            // Second: Try to recover with backup
            .exceptionallyCompose(ex -> {
                System.out.println("Attempting backup recovery...");
                return fetchUserFromBackupService(userId);
            })
            // Third: If backup also fails, return empty user
            .exceptionally(ex -> {
                System.err.println("All sources failed, returning empty user");
                return User.empty();
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOM EXCEPTIONS
    // ─────────────────────────────────────────────────────────────────────────

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String message) {
            super(message);
        }
    }

    public static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) {
            super(message);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
