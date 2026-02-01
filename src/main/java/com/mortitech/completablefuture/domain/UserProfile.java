package com.mortitech.completablefuture.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Enriched user profile combining data from multiple sources.
 * This is the result of async orchestration - combining user data,
 * preferences, and activity history.
 */
public record UserProfile(
    User user,
    List<String> preferences,
    LocalDateTime lastLoginAt,
    int loyaltyPoints,
    String recommendedPlan
) {
    public static UserProfile of(User user, List<String> preferences,
                                  LocalDateTime lastLoginAt, int loyaltyPoints,
                                  String recommendedPlan) {
        return new UserProfile(user, preferences, lastLoginAt, loyaltyPoints, recommendedPlan);
    }
}
