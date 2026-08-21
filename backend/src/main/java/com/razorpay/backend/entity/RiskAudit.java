package com.razorpay.backend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
public class RiskAudit {

    @Id
    private String transactionId;
    private String customerId;
    private BigDecimal orderAmount;
    private double riskScore;
    private String actionTaken;
    private String decisionTrace;
    private BigDecimal interventionCost;
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public static Builder builder() { return new Builder(); }

    // Getters
    public String getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public double getRiskScore() { return riskScore; }
    public String getActionTaken() { return actionTaken; }
    public String getDecisionTrace() { return decisionTrace; }
    public BigDecimal getInterventionCost() { return interventionCost; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public static class Builder {
        private RiskAudit audit = new RiskAudit();

        public Builder transactionId(String id) { audit.transactionId = id; return this; }
        public Builder customerId(String id) { audit.customerId = id; return this; }
        public Builder orderAmount(BigDecimal amt) { audit.orderAmount = amt; return this; }
        public Builder riskScore(double score) { audit.riskScore = score; return this; }
        public Builder actionTaken(String action) { audit.actionTaken = action; return this; }
        public Builder decisionTrace(String trace) { audit.decisionTrace = trace; return this; }
        public Builder interventionCost(BigDecimal cost) { audit.interventionCost = cost; return this; }
        public RiskAudit build() { return audit; }
    }
}