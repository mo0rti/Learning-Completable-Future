package com.mortitech.completablefuture.level3_parallel;

import com.mortitech.completablefuture.domain.DashboardData;
import com.mortitech.completablefuture.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Level 3 - Parallel Execution patterns.
 */
class Level3_ParallelExecutionTest {

    private Level3_ParallelExecution examples;

    @BeforeEach
    void setUp() {
        examples = new Level3_ParallelExecution();
    }

    @AfterEach
    void tearDown() {
        examples.shutdown();
    }

    @Test
    @DisplayName("allOf loads all dashboard data in parallel")
    void testLoadDashboardWithAllOf() {
        // When: loading dashboard with allOf
        DashboardData dashboard = examples.loadDashboardWithAllOf(1L).join();

        // Then: all data is present
        assertEquals("alice", dashboard.user().username());
        assertEquals(2, dashboard.recentOrders().size());
        assertEquals(2, dashboard.recentPayments().size());
        assertEquals("GOOD", dashboard.accountHealth());
    }

    @Test
    @DisplayName("thenCombine combines two futures correctly")
    void testGetUserOrderSummary() {
        // When: combining user and orders
        String summary = examples.getUserOrderSummary(1L).join();

        // Then: summary contains both pieces of data
        assertEquals("alice has 2 orders", summary);
    }

    @Test
    @DisplayName("Chained thenCombine produces same result as allOf")
    void testLoadDashboardWithCombine() {
        // When: using chained thenCombine
        DashboardData dashboard = examples.loadDashboardWithCombine(1L).join();

        // Then: same result as allOf approach
        assertEquals("alice", dashboard.user().username());
        assertEquals(2, dashboard.recentOrders().size());
        assertEquals(2, dashboard.recentPayments().size());
    }

    @Test
    @DisplayName("anyOf returns first completed result")
    void testFetchUserFromFastestSource() {
        // When: racing two data sources
        User user = examples.fetchUserFromFastestSource(1L).join();

        // Then: we get a user (from whichever source was faster)
        assertNotNull(user);
        assertTrue(user.username().startsWith("alice"));
    }

    @Test
    @DisplayName("Virtual threads executor works correctly")
    void testLoadDashboardWithVirtualThreads() {
        // When: using virtual threads
        DashboardData dashboard = examples.loadDashboardWithVirtualThreads(1L).join();

        // Then: all data loaded correctly
        assertEquals("alice", dashboard.user().username());
        assertEquals(2, dashboard.recentOrders().size());
    }

    @Test
    @DisplayName("Parallel execution is faster than sequential")
    void testParallelVsSequential() {
        // Given: timing both approaches
        long sequentialStart = System.currentTimeMillis();
        examples.loadDashboardSequential_SLOW(1L).join();
        long sequentialTime = System.currentTimeMillis() - sequentialStart;

        long parallelStart = System.currentTimeMillis();
        examples.loadDashboardParallel_FAST(1L).join();
        long parallelTime = System.currentTimeMillis() - parallelStart;

        // Then: parallel should be significantly faster
        // Sequential: ~370ms (100 + 150 + 120)
        // Parallel: ~150ms (max of all three)
        System.out.printf("Sequential: %dms, Parallel: %dms%n", sequentialTime, parallelTime);

        // Parallel should be at least 50% faster
        assertTrue(parallelTime < sequentialTime * 0.7,
            String.format("Parallel (%dms) should be much faster than sequential (%dms)",
                parallelTime, sequentialTime));
    }

    @Test
    @DisplayName("Custom executor uses named threads")
    void testCustomExecutorThreadNaming() {
        // When: executing with custom executor
        var threadNameHolder = new String[1];

        examples.fetchUser(1L, examples.getIoExecutor())
            .thenAccept(user -> threadNameHolder[0] = Thread.currentThread().getName())
            .join();

        // Then: thread name has our custom prefix
        assertTrue(threadNameHolder[0].startsWith("io-pool"),
            "Thread name should start with 'io-pool', was: " + threadNameHolder[0]);
    }

    @Test
    @DisplayName("All futures complete before allOf returns")
    void testAllOfCompletionOrder() {
        // Given: flags to track completion
        var completed = new boolean[3];

        var f1 = examples.fetchUser(1L, examples.getIoExecutor())
            .thenAccept(u -> completed[0] = true);
        var f2 = examples.fetchOrders(1L, examples.getIoExecutor())
            .thenAccept(o -> completed[1] = true);
        var f3 = examples.fetchPayments(1L, examples.getIoExecutor())
            .thenAccept(p -> completed[2] = true);

        // When: waiting with allOf
        java.util.concurrent.CompletableFuture.allOf(f1, f2, f3).join();

        // Then: ALL should be completed
        assertTrue(completed[0], "User should be fetched");
        assertTrue(completed[1], "Orders should be fetched");
        assertTrue(completed[2], "Payments should be fetched");
    }
}
