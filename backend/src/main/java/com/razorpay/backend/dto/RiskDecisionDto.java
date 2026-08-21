package com.razorpay.backend.dto;

import java.math.BigDecimal;

public record RiskDecisionDto(
        String action,
        double riskScore,
        String reason,
        BigDecimal interventionCost,
        boolean servedByAi
) {}