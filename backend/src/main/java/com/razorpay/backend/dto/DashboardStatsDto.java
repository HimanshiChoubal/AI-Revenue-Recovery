package com.razorpay.backend.dto;

import java.math.BigDecimal;

public record DashboardStatsDto(
        BigDecimal totalExposedGMV,
        BigDecimal totalSavedGMV,
        BigDecimal totalFalsePositiveCost,
        BigDecimal netEconomicBenefit,
        long totalEvents,
        double testPrecision,
        double testRecall,
        double operatingThreshold,
        boolean aiEngineOnline
) {}