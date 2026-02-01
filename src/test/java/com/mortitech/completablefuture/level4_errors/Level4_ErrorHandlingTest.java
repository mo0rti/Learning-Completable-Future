package com.mortitech.completablefuture.level4_errors;

import com.mortitech.completablefuture.domain.User;
import com.mortitech.completablefuture.level4_errors.Level4_ErrorHandling.DashboardResult;
import com.mortitech.completablefuture.level4_errors.Level4_ErrorHandling.UserResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Level 4 - Error Handling patterns.
 */
class Level4_ErrorHandlingTest {

    private Level4_ErrorHandling examples;

    @BeforeEach
    void setUp() {
        examples = new Level4_ErrorHandling();
    }

    @Test
    @DisplayName("exceptionally returns fallback on error")
    void testExceptionallyFallback() {
        // When: fetching a user that will fail
        User user = examples.fetchUserWithFallback(1L).join();

        // Then: we get the fallback (empty) user
        assertEquals("unknown", user.username());
        assertEquals(0L, user.id());
    }

    @Test
    @DisplayName("Success case bypasses exceptionally")
    void testExceptionallyNotCalledOnSuccess() {
        // When: operation succeeds
        User user = examples.fetchUserThatMayFail(1L, false).join();

        // Then: we get the actual user
        assertEquals("bob", user.username());
    }

    @Test
    @DisplayName("handle wraps success in result")
    void testHandleSuccess() {
        // When: successful operation with handle
        UserResult result = examples.fetchUserWithResultWrapper(1L, false).join();

        // Then: success is wrapped
        assertTrue(result.success());
        assertEquals("bob", result.user().username());
        assertNull(result.errorMessage());
    }

    @Test
    @DisplayName("handle wraps failure in result")
    void testHandleFailure() {
        // When: failed operation with handle
        UserResult result = examples.fetchUserWithResultWrapper(1L, true).join();

        // Then: failure is wrapped
        assertFalse(result.success());
        assertNull(result.user());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("User not found"));
    }

    @Test
    @DisplayName("whenComplete logs but doesn't transform result")
    void testWhenCompleteSuccess() {
        // When: successful operation with whenComplete
        User user = examples.fetchUserWithLogging(1L, false).join();

        // Then: original result is preserved
        assertEquals("bob", user.username());
    }

    @Test
    @DisplayName("whenComplete preserves exception")
    void testWhenCompleteFailure() {
        // When: failed operation with whenComplete
        var future = examples.fetchUserWithLogging(1L, true);

        // Then: exception is still thrown
        assertThrows(CompletionException.class, future::join);
    }

    @Test
    @DisplayName("exceptionallyCompose falls back to backup service")
    void testBackupFallback() {
        // When: primary fails, backup is tried
        User user = examples.fetchUserWithBackupFallback(1L).join();

        // Then: we get user from backup
        assertEquals("bob_backup", user.username());
        assertTrue(user.email().contains("backup"));
    }

    @Test
    @DisplayName("Partial failure handling preserves successful results")
    void testPartialFailure() {
        // When: loading dashboard where orders service fails
        DashboardResult result = examples.loadDashboardWithPartialFailure(1L).join();

        // Then: user and payments succeed, orders fail
        assertTrue(result.user().success(), "User should succeed");
        assertFalse(result.orders().success(), "Orders should fail");
        assertTrue(result.payments().success(), "Payments should succeed");

        // And: we can check overall status
        assertFalse(result.isFullySuccessful());
        assertTrue(result.isPartiallySuccessful());

        // And: successful data is accessible
        assertEquals("bob", result.user().data().username());
        assertEquals("3 successful payments", result.payments().data());

        // And: error message is accessible
        assertTrue(result.orders().error().contains("Orders service down"));
    }

    @Test
    @DisplayName("Chained error handlers recover gracefully")
    void testChainedErrorHandling() {
        // When: using chained error handlers
        User user = examples.fetchUserWithChainedErrorHandling(1L).join();

        // Then: we eventually get a user (from backup, since primary fails)
        assertNotNull(user);
        // Backup service is tried after primary fails
        assertEquals("bob_backup", user.username());
    }

    @Test
    @DisplayName("Typed error handling distinguishes exception types")
    void testTypedErrorHandling() {
        // When: UserNotFoundException occurs
        User user = examples.fetchUserWithTypedErrorHandling(1L, true).join();

        // Then: we get empty user (specific handling for UserNotFoundException)
        assertEquals("unknown", user.username());
    }

    @Test
    @DisplayName("CompletionException wraps the actual cause")
    void testExceptionUnwrapping() {
        // When: an operation fails
        var future = examples.fetchUserThatMayFail(1L, true);

        // Then: CompletionException wraps the actual cause
        CompletionException thrown = assertThrows(CompletionException.class, future::join);

        // And: we can get the actual cause
        assertInstanceOf(Level4_ErrorHandling.UserNotFoundException.class, thrown.getCause());
    }
}
