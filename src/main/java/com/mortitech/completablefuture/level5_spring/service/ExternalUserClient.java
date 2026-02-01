package com.mortitech.completablefuture.level5_spring.service;

import com.mortitech.completablefuture.domain.User;
import org.springframework.stereotype.Component;

/**
 * Simulates an external HTTP client (e.g., RestTemplate, WebClient, Feign).
 * In production, this would make actual HTTP calls.
 */
@Component
public class ExternalUserClient {

    public User fetchUser(Long userId) {
        // Simulate network latency
        simulateLatency(100);
        return User.of(userId, "spring_user", "spring@example.com", "PREMIUM");
    }

    public User fetchUserSlow(Long userId) {
        // Simulate slow response
        simulateLatency(500);
        return User.of(userId, "slow_user", "slow@example.com", "BASIC");
    }

    public User fetchUserThatFails(Long userId) {
        simulateLatency(50);
        throw new RuntimeException("External service unavailable");
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
