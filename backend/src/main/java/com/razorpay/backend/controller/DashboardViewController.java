package com.razorpay.backend.controller;

import com.razorpay.backend.repository.RiskAuditRepository;
import com.razorpay.backend.service.RiskOrchestrationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardViewController {

    private final RiskOrchestrationService orchestrationService;
    private final RiskAuditRepository auditRepository;

    public DashboardViewController(RiskOrchestrationService orchestrationService, RiskAuditRepository auditRepository) {
        this.orchestrationService = orchestrationService;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("stats", orchestrationService.getDashboardStats());
        model.addAttribute("audits", auditRepository.findTop50ByOrderByCreatedAtDesc());
        return "dashboard";
    }
}