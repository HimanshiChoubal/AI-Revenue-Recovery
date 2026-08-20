package com.razorpay.backend.controller;

import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.repository.RecoveryAuditRepository;
import com.razorpay.backend.service.RecoveryOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * JSON ingestion endpoints plus the HTMX-polled partials that keep the
 * dashboard live (metric cards + audit ledger table body).
 */
@Controller
@RequestMapping("/api/v1")
public class RecoveryApiController {

    private static final Logger log = LoggerFactory.getLogger(RecoveryApiController.class);

    private final RecoveryOrchestrationService orchestrationService;
    private final RecoveryAuditRepository auditRepository;

    public RecoveryApiController(RecoveryOrchestrationService orchestrationService,
                                 RecoveryAuditRepository auditRepository) {
        this.orchestrationService = orchestrationService;
        this.auditRepository = auditRepository;
    }

    @PostMapping("/failures/ingest")
    @ResponseBody
    public ResponseEntity<RecoveryAudit> ingestSingle(@RequestBody PaymentFailureEventDto event) {
        if (event == null || event.transactionId() == null || event.transactionId().isBlank()) {
            log.warn("Rejected single ingest request: missing transaction_id");
            return ResponseEntity.badRequest().build();
        }

        try {
            RecoveryAudit audit = orchestrationService.processFailure(event);
            return ResponseEntity.status(HttpStatus.CREATED).body(audit);
        } catch (Exception e) {
            log.error("Failed to ingest single failure event for txn={}", event.transactionId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/failures/ingest-batch")
    @ResponseBody
    public ResponseEntity<List<RecoveryAudit>> ingestBatch(@RequestBody List<PaymentFailureEventDto> events) {
        if (events == null || events.isEmpty()) {
            log.warn("Rejected batch ingest request: empty payload");
            return ResponseEntity.badRequest().build();
        }

        log.info("Ingesting batch of {} failure events via virtual threads", events.size());
        List<RecoveryAudit> results = orchestrationService.processBatch(events);
        return ResponseEntity.status(HttpStatus.CREATED).body(results);
    }

    @GetMapping("/dashboard/stats")
    @ResponseBody
    public ResponseEntity<DashboardStatsDto> dashboardStats() {
        return ResponseEntity.ok(orchestrationService.getDashboardStats());
    }

    @GetMapping("/dashboard/audit-rows")
    public String auditRows(Model model) {
        List<RecoveryAudit> audits = auditRepository.findTop50ByOrderByCreatedAtDesc();
        model.addAttribute("audits", audits);
        return "fragments/audit_rows :: rows";
    }
}