package com.razorpay.backend.dto;

import java.math.BigDecimal;

/**
 * Mirrors the JSON response returned by the ReCover-AI diagnostic
 * & policy engine (POST /api/v1/diagnose-and-plan).
 */
public record RecoveryDecisionDto(
        String action,
        String reason,
        BigDecimal costInr,
        String outreachScript,
        String targetRail
) {
}