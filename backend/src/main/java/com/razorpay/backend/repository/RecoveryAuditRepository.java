package com.razorpay.backend.repository;

import com.razorpay.backend.entity.RecoveryAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface RecoveryAuditRepository extends JpaRepository<RecoveryAudit, Long> {

    List<RecoveryAudit> findTop50ByOrderByCreatedAtDesc();

    /**
     * Sum of transaction amounts still pending recovery (status = 'PENDING').
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RecoveryAudit r WHERE r.status = 'PENDING'")
    BigDecimal getTotalAtRisk();

    /**
     * Sum of amounts successfully recovered (status = 'RECOVERED').
     */
    @Query("SELECT COALESCE(SUM(r.recoveredAmount), 0) FROM RecoveryAudit r WHERE r.status = 'RECOVERED'")
    BigDecimal getTotalRecovered();

    /**
     * Sum of intervention cost incurred across all recovery attempts.
     */
    @Query("SELECT COALESCE(SUM(r.interventionCost), 0) FROM RecoveryAudit r")
    BigDecimal getTotalCost();

    List<RecoveryAudit> findByStatus(String status);

    List<RecoveryAudit> findByTransactionId(String transactionId);
}