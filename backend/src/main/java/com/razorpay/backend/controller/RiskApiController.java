package com.razorpay.backend.controller;

import com.razorpay.backend.repository.RiskAuditRepository;
import com.razorpay.backend.service.RiskOrchestrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
@RequestMapping("/api/v1/dashboard")
public class RiskApiController {

    private final RiskOrchestrationService orchestrationService;
    private final RiskAuditRepository auditRepository;

    public RiskApiController(RiskOrchestrationService orchestrationService, RiskAuditRepository auditRepository) {
        this.orchestrationService = orchestrationService;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/metrics")
    public ModelAndView getMetrics() {
        return new ModelAndView("fragments/metrics_cards", "stats", orchestrationService.getDashboardStats());
    }

    @GetMapping("/audit-rows")
    public ModelAndView getAuditRows() {
        return new ModelAndView("fragments/audit_rows", "audits", auditRepository.findTop50ByOrderByCreatedAtDesc());
    }

    @PostMapping("/trigger-batch")
    public String triggerBatch() {
        orchestrationService.triggerSyntheticBatch();
        return "Processing 500 simulated returns...";
    }
}