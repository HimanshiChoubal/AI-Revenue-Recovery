package com.razorpay.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recovery_audit")
public class RecoveryAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "action_taken")
    private String actionTaken;

    @Column(name = "decision_trace", length = 1000)
    private String decisionTrace;

    @Column(name = "intervention_cost", precision = 8, scale = 2)
    private BigDecimal interventionCost;

    @Column(name = "recovered_amount", precision = 12, scale = 2)
    private BigDecimal recoveredAmount;

    @Column(name = "status")
    private String status;

    @Column(name = "payment_link_url")
    private String paymentLinkUrl;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public RecoveryAudit() {
    }

    public RecoveryAudit(String transactionId, String customerName, String customerPhone,
                         BigDecimal amount, String errorCode, String actionTaken,
                         String decisionTrace, BigDecimal interventionCost,
                         BigDecimal recoveredAmount, String status, String paymentLinkUrl) {
        this.transactionId = transactionId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.amount = amount;
        this.errorCode = errorCode;
        this.actionTaken = actionTaken;
        this.decisionTrace = decisionTrace;
        this.interventionCost = interventionCost;
        this.recoveredAmount = recoveredAmount;
        this.status = status;
        this.paymentLinkUrl = paymentLinkUrl;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for assembling an immutable audit ledger row before
     * it is persisted. createdAt is stamped automatically on save via
     * {@link #onCreate()} and is intentionally not settable here.
     */
    public static final class Builder {
        private String transactionId;
        private String customerName;
        private String customerPhone;
        private BigDecimal amount;
        private String errorCode;
        private String actionTaken;
        private String decisionTrace;
        private BigDecimal interventionCost;
        private BigDecimal recoveredAmount;
        private String status;
        private String paymentLinkUrl;

        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder customerName(String customerName) {
            this.customerName = customerName;
            return this;
        }

        public Builder customerPhone(String customerPhone) {
            this.customerPhone = customerPhone;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder actionTaken(String actionTaken) {
            this.actionTaken = actionTaken;
            return this;
        }

        public Builder decisionTrace(String decisionTrace) {
            this.decisionTrace = decisionTrace;
            return this;
        }

        public Builder interventionCost(BigDecimal interventionCost) {
            this.interventionCost = interventionCost;
            return this;
        }

        public Builder recoveredAmount(BigDecimal recoveredAmount) {
            this.recoveredAmount = recoveredAmount;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder paymentLinkUrl(String paymentLinkUrl) {
            this.paymentLinkUrl = paymentLinkUrl;
            return this;
        }

        public RecoveryAudit build() {
            return new RecoveryAudit(transactionId, customerName, customerPhone, amount, errorCode,
                    actionTaken, decisionTrace, interventionCost, recoveredAmount, status, paymentLinkUrl);
        }
    }

    // --- Getters & setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getActionTaken() {
        return actionTaken;
    }

    public void setActionTaken(String actionTaken) {
        this.actionTaken = actionTaken;
    }

    public String getDecisionTrace() {
        return decisionTrace;
    }

    public void setDecisionTrace(String decisionTrace) {
        this.decisionTrace = decisionTrace;
    }

    public BigDecimal getInterventionCost() {
        return interventionCost;
    }

    public void setInterventionCost(BigDecimal interventionCost) {
        this.interventionCost = interventionCost;
    }

    public BigDecimal getRecoveredAmount() {
        return recoveredAmount;
    }

    public void setRecoveredAmount(BigDecimal recoveredAmount) {
        this.recoveredAmount = recoveredAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPaymentLinkUrl() {
        return paymentLinkUrl;
    }

    public void setPaymentLinkUrl(String paymentLinkUrl) {
        this.paymentLinkUrl = paymentLinkUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}