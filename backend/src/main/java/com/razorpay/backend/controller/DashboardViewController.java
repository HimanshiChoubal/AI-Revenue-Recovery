package com.razorpay.backend.controller;

import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.repository.RecoveryAuditRepository;
import com.razorpay.backend.service.RecoveryOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Serves the server-rendered Thymeleaf dashboard shell. Subsequent live
 * updates (metric cards, audit ledger rows) are fetched via HTMX polling
 * against {@link RecoveryApiController}.
 */
@Controller
public class DashboardViewController {

    private static final Logger log = LoggerFactory.getLogger(DashboardViewController.class);

    private final RecoveryOrchestrationService orchestrationService;
    private final RecoveryAuditRepository auditRepository;

    public DashboardViewController(RecoveryOrchestrationService orchestrationService,
                                   RecoveryAuditRepository auditRepository) {
        this.orchestrationService = orchestrationService;
        this.auditRepository = auditRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        DashboardStatsDto stats = orchestrationService.getDashboardStats();
        List<RecoveryAudit> audits = auditRepository.findTop50ByOrderByCreatedAtDesc();

        model.addAttribute("stats", stats);
        model.addAttribute("audits", audits);

        log.debug("Rendering dashboard shell with {} recent audit rows", audits.size());
        return "dashboard";
    }
}