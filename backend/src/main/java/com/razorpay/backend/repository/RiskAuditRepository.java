package com.razorpay.backend.repository;

import com.razorpay.backend.entity.RiskAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;

public interface RiskAuditRepository extends JpaRepository<RiskAudit, String> {

    List<RiskAudit> findTop50ByOrderByCreatedAtDesc();

    @Query("SELECT COALESCE(SUM(r.orderAmount), 0) FROM RiskAudit r")
    BigDecimal getTotalExposedGMV();

    @Query("SELECT COALESCE(SUM(r.orderAmount), 0) FROM RiskAudit r WHERE r.actionTaken = 'MANUAL_REVIEW'")
    BigDecimal getTotalSavedGMV();

    @Query("SELECT COALESCE(SUM(r.interventionCost), 0) FROM RiskAudit r")
    BigDecimal getTotalFalsePositiveCost();
}