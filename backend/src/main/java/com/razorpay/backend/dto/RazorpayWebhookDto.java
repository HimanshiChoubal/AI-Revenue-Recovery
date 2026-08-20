package com.razorpay.backend.dto;

import java.math.BigDecimal;

/**
 * Flattened view of the fields ReCover-AI cares about from a Razorpay
 * `payment.failed` webhook event payload.
 */
public record RazorpayWebhookDto(
        String event,
        String paymentId,
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String errorCode,
        String errorDescription,
        String contact,
        String email,
        String createdAt
) {
}