package com.razorpay.backend.dto;

import java.math.BigDecimal;

public record ActionBreakdown(
        String actionTaken,
        long count,
        double recoveryRate,
        BigDecimal totalCost
) {
    // Constructor matching JPQL types (Double from arithmetic expression, Number/BigDecimal from SUM)
    public ActionBreakdown(String actionTaken, long count, Double recoveryRate, Number totalCost) {
        this(
                actionTaken,
                count,
                recoveryRate != null ? Math.round(recoveryRate * 100.0) / 100.0 : 0.0,
                totalCost != null ? BigDecimal.valueOf(totalCost.doubleValue()) : BigDecimal.ZERO
        );
    }
}