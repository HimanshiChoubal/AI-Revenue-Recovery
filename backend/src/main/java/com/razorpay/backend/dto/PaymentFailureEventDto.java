package com.razorpay.backend.dto;

import java.math.BigDecimal;

/**
 * Normalized representation of a single payment failure, as forwarded
 * internally to the ReCover-AI diagnostic engine.
 */
public record PaymentFailureEventDto(
        String transactionId,
        BigDecimal amount,
        String errorCode,
        String customerName,
        String customerPhone,
        int attemptsSoFar
) {
}