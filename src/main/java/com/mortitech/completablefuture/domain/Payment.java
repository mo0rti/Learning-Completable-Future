package com.mortitech.completablefuture.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment information typically fetched from a payment gateway.
 * This call is often slow (external API) - perfect candidate for async.
 */
public record Payment(
    Long id,
    Long orderId,
    BigDecimal amount,
    String status,          // PENDING, COMPLETED, FAILED, REFUNDED
    String paymentMethod,   // CARD, PAYPAL, BANK_TRANSFER
    LocalDateTime processedAt
) {
    public static Payment of(Long id, Long orderId, BigDecimal amount,
                             String status, String paymentMethod, LocalDateTime processedAt) {
        return new Payment(id, orderId, amount, status, paymentMethod, processedAt);
    }
}
