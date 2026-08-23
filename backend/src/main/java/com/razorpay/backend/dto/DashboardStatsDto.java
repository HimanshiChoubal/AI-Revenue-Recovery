package com.razorpay.backend.dto;

import java.math.BigDecimal;

public record DashboardStatsDto(
        BigDecimal totalAtRisk,
        BigDecimal totalRecovered,
        BigDecimal totalCost,
        long totalTransactions,
        double recoveryRate,
        BigDecimal incrementalRecoveryValue,
        double roiMultiple
) {}