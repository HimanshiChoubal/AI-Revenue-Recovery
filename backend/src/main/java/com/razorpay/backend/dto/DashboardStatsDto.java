package com.razorpay.backend.dto;

import java.math.BigDecimal;

/**
 * Aggregate metrics surfaced on the ReCover-AI dashboard.
 */
public record DashboardStatsDto(
        BigDecimal totalAtRisk,
        BigDecimal totalRecovered,
        BigDecimal totalCost,
        long totalTransactions,
        double recoveryRate
) {
}