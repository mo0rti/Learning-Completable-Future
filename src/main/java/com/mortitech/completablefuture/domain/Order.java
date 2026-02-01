package com.mortitech.completablefuture.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a customer order.
 * In real scenarios, order data often comes from a separate microservice.
 */
public record Order(
    Long id,
    Long userId,
    List<String> items,
    BigDecimal totalAmount,
    String status,          // PENDING, CONFIRMED, SHIPPED, DELIVERED
    LocalDateTime createdAt
) {
    public static Order of(Long id, Long userId, List<String> items,
                           BigDecimal totalAmount, String status, LocalDateTime createdAt) {
        return new Order(id, userId, items, totalAmount, status, createdAt);
    }
}
