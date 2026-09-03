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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequestMapping("/api/v1")
public class RecoveryApiController {

    private static final Logger log = LoggerFactory.getLogger(RecoveryApiController.class);
    private static final int DEFAULT_BENCHMARK_SIZE = 1000;
    private static final int DEMO_BATCH_SIZE = 8;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            // Single manual ingests are rare, low-volume — safe to use the real API.
            RecoveryAudit audit = orchestrationService.processFailure(event, true);
            return ResponseEntity.status(HttpStatus.CREATED).body(audit);
        } catch (Exception e) {
            log.error("Failed to ingest single failure event for txn={}", event.transactionId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * ONLY method mapped to this path — the earlier duplicate is gone.
     * useRealApi is derived from size, never trusted from the client
     * directly: only an explicit small ?size (<= DEMO_BATCH_SIZE) ever
     * touches the real Razorpay API. Every other call — including
     * generator.py's default 1000-txn / body-only requests with no size
     * param — is mock-only, no network call, no rate-limit risk.
     */
    @PostMapping(value = "/failures/ingest-batch", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> ingestBatch(
            @RequestBody(required = false) String rawBody,
            @RequestParam(required = false) Integer size) throws com.fasterxml.jackson.core.JsonProcessingException {

        try {
            List<PaymentFailureEventDto> events = null;
            if (rawBody != null && !rawBody.isBlank()) {
                events = objectMapper.readValue(rawBody,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, PaymentFailureEventDto.class));
            }

            List<RecoveryAudit> results;
            int requestedSize;
            boolean useRealApi = (size != null && size > 0 && size <= DEMO_BATCH_SIZE);

            if (events == null || events.isEmpty()) {
                requestedSize = (size != null && size > 0) ? size : DEFAULT_BENCHMARK_SIZE;
                log.info("Empty batch request received — generating {} synthetic transactions (useRealApi={})",
                        requestedSize, useRealApi);
                results = orchestrationService.processBatch(requestedSize, useRealApi);
            } else {
                requestedSize = events.size();
                log.info("Ingesting batch of {} failure events via virtual threads (useRealApi={})",
                        requestedSize, useRealApi);
                results = orchestrationService.processBatch(events, useRealApi);
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