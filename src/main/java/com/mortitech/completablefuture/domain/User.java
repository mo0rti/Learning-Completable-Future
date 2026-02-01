package com.mortitech.completablefuture.domain;

/**
 * Core domain entity representing a user in our system.
 * Used throughout all examples to demonstrate real-world scenarios.
 */
public record User(
    Long id,
    String username,
    String email,
    String tier  // BASIC, PREMIUM, VIP - affects processing priority
) {
    public static User of(Long id, String username, String email, String tier) {
        return new User(id, username, email, tier);
    }

    public static User empty() {
        return new User(0L, "unknown", "unknown@example.com", "BASIC");
    }
}
