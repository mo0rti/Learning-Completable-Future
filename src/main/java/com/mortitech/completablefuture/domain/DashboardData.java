package com.mortitech.completablefuture.domain;

import java.util.List;

/**
 * Aggregated dashboard data combining multiple async sources.
 * This is the typical use case for parallel CompletableFuture execution:
 * fetching user, orders, and payments concurrently then combining.
 */
public record DashboardData(
    User user,
    List<Order> recentOrders,
    List<Payment> recentPayments,
    int totalOrders,
    String accountHealth  // GOOD, WARNING, CRITICAL
) {
    public static DashboardData of(User user, List<Order> recentOrders,
                                    List<Payment> recentPayments, int totalOrders,
                                    String accountHealth) {
        return new DashboardData(user, recentOrders, recentPayments, totalOrders, accountHealth);
    }
}
