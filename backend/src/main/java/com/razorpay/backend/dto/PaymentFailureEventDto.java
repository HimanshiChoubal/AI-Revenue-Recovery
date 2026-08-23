package com.razorpay.backend.dto;

import java.math.BigDecimal;

/**
 * Normalized representation of a single payment failure, sent to the
 * AI diagnostic engine and processed by RecoveryOrchestrationService.
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