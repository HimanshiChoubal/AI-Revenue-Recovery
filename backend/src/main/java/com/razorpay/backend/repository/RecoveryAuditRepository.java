package com.razorpay.backend.repository;

import com.razorpay.backend.entity.RecoveryAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.razorpay.backend.dto.ActionBreakdown;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface RecoveryAuditRepository extends JpaRepository<RecoveryAudit, Long> {

    List<RecoveryAudit> findTop50ByOrderByCreatedAtDesc();
    List<RecoveryAudit> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff);
    /**
     * Sum of transaction amounts still pending recovery (status = 'PENDING').
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RecoveryAudit r")
    BigDecimal getTotalAtRisk();
    /**
     * Sum of amounts successfully recovered (status = 'RECOVERED').
     */
    @Query("SELECT COALESCE(SUM(r.recoveredAmount), 0) FROM RecoveryAudit r WHERE r.status IN ('RECOVERED', 'CONFIRMED_RECOVERED')")
    BigDecimal getTotalRecovered();


    List<RecoveryAudit> findByPaymentLinkId(String paymentLinkId);

    long countByPaymentLinkIdIsNotNull();

    /**
     * Sum of intervention cost incurred across all recovery attempts.
     */
    @Query("SELECT COALESCE(SUM(r.interventionCost), 0) FROM RecoveryAudit r")
    BigDecimal getTotalCost();

    List<RecoveryAudit> findByStatus(String status);

    List<RecoveryAudit> findByTransactionId(String transactionId);
    List<RecoveryAudit> findByPaymentLinkUrl(String paymentLinkUrl);
    @Query("SELECT new com.razorpay.backend.dto.ActionBreakdown(" +
            "r.actionTaken, " +
            "COUNT(r), " +
            "(SUM(CASE WHEN r.status = 'RECOVERED' THEN 1.0 ELSE 0.0 END) * 100.0 / COUNT(r)), " +
            "COALESCE(SUM(r.interventionCost), 0)) " +
            "FROM RecoveryAudit r " +
            "GROUP BY r.actionTaken")
    List<ActionBreakdown> getActionBreakdown();
}