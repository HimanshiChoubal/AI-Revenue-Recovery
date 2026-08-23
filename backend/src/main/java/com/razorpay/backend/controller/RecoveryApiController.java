package com.razorpay.backend.controller;

import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.repository.RecoveryAuditRepository;
import com.razorpay.backend.service.RecoveryOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private static final int DEFAULT_BENCHMARK_SIZE = 1000;

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

    /**
     * Ingest and process a batch of failure events. If the request body is
     * empty/null (as sent by the dashboard's "Trigger Batch Benchmark"
     * button, which POSTs with no body), automatically generates and
     * processes {@value #DEFAULT_BENCHMARK_SIZE} synthetic transactions
     * instead of rejecting the request. Always returns an HTML snippet
     * suitable for direct HTMX swap into #benchmark-status.
     */
    @PostMapping(value = "/failures/ingest-batch", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> ingestBatch(
            @RequestBody(required = false) List<PaymentFailureEventDto> events) {

        try {
            List<RecoveryAudit> results;
            int requestedSize;

            if (events == null || events.isEmpty()) {
                requestedSize = DEFAULT_BENCHMARK_SIZE;
                log.info("Empty batch request received — generating {} synthetic transactions", requestedSize);
                results = orchestrationService.processBatch(requestedSize);
            } else {
                requestedSize = events.size();
                log.info("Ingesting batch of {} failure events via virtual threads", requestedSize);
                results = orchestrationService.processBatch(events);
            }

            long recoveredCount = results.stream()
                    .filter(r -> "RECOVERED".equals(r.getStatus()))
                    .count();

            String message = String.format(
                    "<span class=\"text-emerald-400\">&#10003; Processed %d / %d transactions " +
                            "&mdash; %d recovered</span>",
                    results.size(), requestedSize, recoveredCount);

            return ResponseEntity.status(HttpStatus.CREATED).body(message);
        } catch (Exception e) {
            log.error("Batch benchmark failed", e);
            String message = "<span class=\"text-rose-400\">&#10007; Batch failed: "
                    + escapeHtml(String.valueOf(e.getMessage())) + "</span>";
            return ResponseEntity.internalServerError().body(message);
        }
    }

    @GetMapping("/dashboard/stats")
    @ResponseBody
    public ResponseEntity<DashboardStatsDto> dashboardStats() {
        return ResponseEntity.ok(orchestrationService.getDashboardStats());
    }

    /**
     * Returns the metric cards as a rendered HTML fragment (not raw JSON)
     * so HTMX can swap it directly into the dashboard's #metrics-grid
     * container on every poll.
     */
    @GetMapping("/dashboard/metrics")
    public String dashboardMetricsFragment(Model model) {
        DashboardStatsDto stats = orchestrationService.getDashboardStats();
        model.addAttribute("stats", stats);
        return "fragments/metrics_cards :: cards";
    }

    @GetMapping("/dashboard/audit-rows")
    public String auditRows(Model model) {
        List<RecoveryAudit> audits = auditRepository.findTop50ByOrderByCreatedAtDesc();
        model.addAttribute("audits", audits);
        return "fragments/audit_rows :: rows";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}