package com.razorpay.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record ReturnEventDto(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("order_amount") BigDecimal orderAmount,
        @JsonProperty("account_age_days") int accountAgeDays,
        @JsonProperty("past_return_rate") double pastReturnRate,
        @JsonProperty("days_since_delivery") int daysSinceDelivery,
        @JsonProperty("discount_pct") double discountPct
) {}