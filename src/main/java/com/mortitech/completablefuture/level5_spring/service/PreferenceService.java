package com.mortitech.completablefuture.level5_spring.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Simulates a preferences service (could be separate microservice).
 */
@Service
public class PreferenceService {

    public List<String> getPreferences(Long userId) {
        simulateLatency(80);
        return List.of("dark_mode", "email_notifications", "weekly_digest");
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
