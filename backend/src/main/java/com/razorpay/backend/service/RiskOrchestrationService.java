package com.razorpay.backend.service;

import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.dto.ReturnEventDto;
import com.razorpay.backend.dto.RiskDecisionDto;
import com.razorpay.backend.entity.RiskAudit;
import com.razorpay.backend.repository.RiskAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RiskOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RiskOrchestrationService.class);

    private final RiskAuditRepository auditRepository;
    private final RestClient aiClient;
    private final String aiEngineUrl;
    private final String aiMetricsUrl;

    public RiskOrchestrationService(
            RiskAuditRepository auditRepository,
            RestClient.Builder restClientBuilder,
            @Value("${ai.engine.url:http://127.0.0.1:8000/api/v1/score-return}") String aiEngineUrl,
            @Value("${ai.metrics.url:http://127.0.0.1:8000/api/v1/model-metrics}") String aiMetricsUrl) {
        this.auditRepository = auditRepository;
        this.aiClient = restClientBuilder.build();
        this.aiEngineUrl = aiEngineUrl;
        this.aiMetricsUrl = aiMetricsUrl;
    }

    public RiskAudit processReturnEvent(ReturnEventDto event) {
        RiskDecisionDto decision = scoreWithFallback(event);

        RiskAudit audit = RiskAudit.builder()
                .transactionId(event.transactionId())
                .customerId(event.customerId())
                .orderAmount(event.orderAmount())
                .riskScore(decision.riskScore())
                .actionTaken(decision.action())
                .decisionTrace(decision.reason())
                .interventionCost(decision.interventionCost())
                .build();

        return auditRepository.save(audit);
    }

    private RiskDecisionDto scoreWithFallback(ReturnEventDto event) {
        try {
            log.info("--> Sending to ML Engine: Txn ID: {}, Amount: {}", event.transactionId(), event.orderAmount());

            RiskDecisionDto decision = aiClient.post()
                    .uri(aiEngineUrl)
                    .body(event)
                    .retrieve()
                    .body(RiskDecisionDto.class);

            log.info("<-- ML Engine Success: Score: {}, Action: {}", decision.riskScore(), decision.action());
            return decision;

        } catch (RestClientResponseException e) {
            // The Python engine was reached, but it rejected the payload (e.g., 422, 500)
            log.error("[ML REJECTED] HTTP {} - Raw Body from FastAPI: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return applyFallbackRule(event, "Fallback: API Rejected Payload");

        } catch (ResourceAccessException e) {
            // The Python engine is not running, wrong port, or connection refused
            log.error("[ML UNREACHABLE] Could not connect to {}. Error: {}", aiEngineUrl, e.getMessage());
            return applyFallbackRule(event, "Fallback: AI Engine Unreachable");

        } catch (Exception e) {
            log.error("[ML UNKNOWN ERROR] {}", e.getMessage());
            return applyFallbackRule(event, "Fallback: Unknown Error");
        }
    }

    private RiskDecisionDto applyFallbackRule(ReturnEventDto event, String traceReason) {
        if (event.orderAmount().compareTo(BigDecimal.valueOf(8000)) > 0) {
            return new RiskDecisionDto("MANUAL_REVIEW", 1.000, traceReason + " (High Value)", BigDecimal.valueOf(500), false);
        }
        return new RiskDecisionDto("AUTO_REFUND", 0.000, traceReason + " (Low Value)", BigDecimal.ZERO, false);
    }

    public DashboardStatsDto getDashboardStats() {
        double testPrecision = 0.0;
        double testRecall = 0.0;
        double operatingThreshold = 0.50;
        boolean aiOnline = false;

        try {
            Map metrics = aiClient.get().uri(aiMetricsUrl).retrieve().body(Map.class);
            if (metrics != null) {
                testPrecision = ((Number) metrics.getOrDefault("precision", 0)).doubleValue() * 100;
                testRecall = ((Number) metrics.getOrDefault("recall", 0)).doubleValue() * 100;
                operatingThreshold = ((Number) metrics.getOrDefault("operating_threshold", 0.50)).doubleValue();
                aiOnline = true;
            }
        } catch (Exception e) {
            log.debug("AI metrics offline");
        }

        BigDecimal exposed = auditRepository.getTotalExposedGMV();
        BigDecimal saved = auditRepository.getTotalSavedGMV();
        BigDecimal fpCost = auditRepository.getTotalFalsePositiveCost();
        BigDecimal netBenefit = saved.subtract(fpCost);

        return new DashboardStatsDto(
                exposed,
                saved,
                fpCost,
                netBenefit,
                auditRepository.count(),
                testPrecision,
                testRecall,
                operatingThreshold,
                aiOnline
        );
    }

    public void triggerSyntheticBatch() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 500; i++) {
                ThreadLocalRandom r = ThreadLocalRandom.current();

                // Mix of typical vs edge/abusive return characteristics
                boolean isSuspicious = r.nextDouble() < 0.12;
                double returnRate = isSuspicious ? r.nextDouble(0.55, 0.95) : r.nextDouble(0.01, 0.25);
                int daysSinceDel = isSuspicious ? r.nextInt(8, 28) : r.nextInt(1, 6);
                int ageDays = isSuspicious ? r.nextInt(2, 45) : r.nextInt(90, 800);
                double amount = isSuspicious ? r.nextDouble(4500, 32000) : r.nextDouble(350, 6500);

                ReturnEventDto dto = new ReturnEventDto(
                        "ret_" + UUID.randomUUID().toString().substring(0, 8),
                        "cust_" + r.nextInt(1000, 9999),
                        BigDecimal.valueOf(amount),
                        ageDays,
                        returnRate,
                        daysSinceDel,
                        r.nextBoolean() ? 0.0 : 20.0
                );
                executor.submit(() -> processReturnEvent(dto));
            }
        }
    }
}