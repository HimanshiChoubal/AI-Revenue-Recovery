package com.razorpay.backend.dto;

import java.math.BigDecimal;

/**
 * Mirrors the JSON response returned by the Python diagnostic & policy
 * engine (POST /api/v1/diagnose-and-plan).
 *
 * action is one of: AUTO_RETRY, WHATSAPP_LINK, VOICE_OUTREACH, ABORT
 */
public record RecoveryDecisionDto(
        String action,
        String reason,
        BigDecimal costInr,
        String outreachScript,
        String targetRail
) {
}