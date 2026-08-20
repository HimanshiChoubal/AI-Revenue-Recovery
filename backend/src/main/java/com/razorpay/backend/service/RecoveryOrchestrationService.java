package com.razorpay.backend.service;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.dto.RecoveryDecisionDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.repository.RecoveryAuditRepository;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Core orchestration layer for ReCover-AI. Talks to the Python diagnostic
 * engine to decide what to do about a failed payment, executes that
 * decision against the Razorpay API when a live customer-facing link is
 * needed, and writes an immutable row to the H2 audit ledger no matter
 * what happens.
 */
@Service
public class RecoveryOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryOrchestrationService.class);

    private static final Set<String> LIVE_LINK_ACTIONS = Set.of("WHATSAPP_SMART_LINK", "HINGLISH_VOICE_OUTREACH");
    private static final String ACTION_ABORT = "ABORT";
    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String STATUS_ABORTED = "ABORTED";
    private static final BigDecimal HIGH_VALUE_THRESHOLD = BigDecimal.valueOf(1500);

    private final RecoveryAuditRepository auditRepository;
    private final RazorpayClient razorpayClient;
    private final RestClient aiEngineClient;
    private final String aiEngineUrl;

    public RecoveryOrchestrationService(
            RecoveryAuditRepository auditRepository,
            RazorpayClient razorpayClient,
            RestClient.Builder restClientBuilder,
            @Value("${ai.engine.url:http://localhost:8000/api/v1/diagnose-and-plan}") String aiEngineUrl) {
        this.auditRepository = auditRepository;
        this.razorpayClient = razorpayClient;
        this.aiEngineUrl = aiEngineUrl;
        this.aiEngineClient = restClientBuilder.build();
    }

    /**
     * Diagnose a single failure, act on the decision, and persist the
     * outcome to the audit ledger. Never throws for a downstream failure
     * (AI engine down, Razorpay API error) — those are captured as part
     * of the audit trail instead.
     */
    public RecoveryAudit processFailure(PaymentFailureEventDto event) {
        if (event == null || event.transactionId() == null) {
            throw new IllegalArgumentException("PaymentFailureEventDto and its transactionId must not be null");
        }

        RecoveryDecisionDto decision = diagnoseWithFallback(event);

        String paymentLinkUrl = null;
        String status;

        if (ACTION_ABORT.equals(decision.action())) {
            status = STATUS_ABORTED;
        } else if (LIVE_LINK_ACTIONS.contains(decision.action())) {
            paymentLinkUrl = createRazorpayPaymentLink(event, decision);
            status = (paymentLinkUrl != null) ? STATUS_RECOVERED : STATUS_ABORTED;
        } else {
            // e.g. AUTO_RETRY_FALLBACK_GATEWAY: silent retry, no customer-facing link needed
            status = STATUS_RECOVERED;
        }

        RecoveryAudit audit = RecoveryAudit.builder()
                .transactionId(event.transactionId())
                .customerName(event.customerName())
                .customerPhone(event.customerPhone())
                .amount(nullSafe(event.amount()))
                .errorCode(event.errorCode())
                .actionTaken(decision.action())
                .decisionTrace(truncate(decision.reason(), 1000))
                .interventionCost(nullSafe(decision.costInr()))
                .recoveredAmount(STATUS_RECOVERED.equals(status) ? nullSafe(event.amount()) : BigDecimal.ZERO)
                .status(status)
                .paymentLinkUrl(paymentLinkUrl)
                .build();

        RecoveryAudit saved = auditRepository.save(audit);
        log.info("Processed txn={} action={} status={} cost=₹{}",
                event.transactionId(), decision.action(), status, decision.costInr());
        return saved;
    }

    /**
     * Fan a batch of failure events (e.g. 1,000 transactions) out across
     * Java 21 virtual threads. Each event gets its own virtual thread, so
     * throughput isn't bottlenecked by a fixed platform-thread pool while
     * waiting on the AI engine and Razorpay HTTP calls.
     */
    public List<RecoveryAudit> processBatch(List<PaymentFailureEventDto> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<RecoveryAudit> results = new ArrayList<>(events.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RecoveryAudit>> futures = new ArrayList<>(events.size());

            for (PaymentFailureEventDto event : events) {
                futures.add(executor.submit(() -> processFailure(event)));
            }

            for (Future<RecoveryAudit> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    log.error("Failed to process an event within batch", e);
                }
            }
        }

        log.info("Batch complete: {} / {} events processed successfully", results.size(), events.size());
        return results;
    }

    /**
     * Compute aggregate recovery metrics for the dashboard.
     */
    public DashboardStatsDto getDashboardStats() {
        BigDecimal totalAtRisk = nullSafe(auditRepository.getTotalAtRisk());
        BigDecimal totalRecovered = nullSafe(auditRepository.getTotalRecovered());
        BigDecimal totalCost = nullSafe(auditRepository.getTotalCost());
        long totalTransactions = auditRepository.count();

        double recoveryRate = 0.0;
        if (totalTransactions > 0) {
            long recoveredCount = auditRepository.findByStatus(STATUS_RECOVERED).size();
            recoveryRate = BigDecimal.valueOf(recoveredCount)
                    .divide(BigDecimal.valueOf(totalTransactions), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        return new DashboardStatsDto(totalAtRisk, totalRecovered, totalCost, totalTransactions, recoveryRate);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Calls the Python diagnostic engine. If it's unreachable or errors
     * out, falls back to a conservative local policy so recovery never
     * silently stalls just because the AI microservice is down.
     */
    private RecoveryDecisionDto diagnoseWithFallback(PaymentFailureEventDto event) {
        try {
            RecoveryDecisionDto decision = aiEngineClient.post()
                    .uri(aiEngineUrl)
                    .body(event)
                    .retrieve()
                    .body(RecoveryDecisionDto.class);

            if (decision == null || decision.action() == null) {
                throw new RestClientException("AI engine returned an empty or invalid response body");
            }
            return decision;
        } catch (RestClientException | IllegalStateException e) {
            log.warn("AI engine unreachable for txn={}, applying local fallback policy. Reason: {}",
                    event.transactionId(), e.getMessage());
            return fallbackDecision(event);
        }
    }

    private RecoveryDecisionDto fallbackDecision(PaymentFailureEventDto event) {
        if (event.attemptsSoFar() >= 3) {
            return new RecoveryDecisionDto(
                    ACTION_ABORT,
                    "AI engine unreachable; max attempts already exhausted, aborting as a safety default.",
                    BigDecimal.ZERO,
                    null,
                    "NONE");
        }

        BigDecimal amount = nullSafe(event.amount());
        if (amount.compareTo(HIGH_VALUE_THRESHOLD) >= 0) {
            return new RecoveryDecisionDto(
                    "HINGLISH_VOICE_OUTREACH",
                    "AI engine unreachable; falling back to voice outreach for a high-value transaction.",
                    BigDecimal.valueOf(1.20),
                    "Namaste! Aapka payment fail ho gaya tha, kripya humare secure link se dobara try karein.",
                    "IVR_VOICE_CALL::" + event.customerPhone());
        }

        return new RecoveryDecisionDto(
                "WHATSAPP_SMART_LINK",
                "AI engine unreachable; falling back to a low-cost WhatsApp smart link.",
                BigDecimal.valueOf(0.35),
                "Hi! Your payment didn't go through. Tap the link below to complete it securely.",
                "WHATSAPP::" + event.customerPhone());
    }

    /**
     * Creates a live Razorpay test payment link (rzp.io) for a customer
     * to complete their failed payment in one click. Returns null (never
     * throws) if link creation fails, so the caller can mark the audit
     * row as ABORTED instead of crashing the batch.
     */
    private String createRazorpayPaymentLink(PaymentFailureEventDto event, RecoveryDecisionDto decision) {
        if (event.amount() == null || event.amount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Skipping payment link creation for txn={}: invalid amount", event.transactionId());
            return null;
        }

        try {
            long amountInPaise = event.amount()
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            JSONObject customer = new JSONObject();
            customer.put("name", event.customerName() != null ? event.customerName() : "Customer");
            customer.put("contact", event.customerPhone() != null ? event.customerPhone() : JSONObject.NULL);

            JSONObject notify = new JSONObject();
            notify.put("sms", true);
            notify.put("email", false);

            JSONObject payload = new JSONObject();
            payload.put("amount", amountInPaise);
            payload.put("currency", "INR");
            payload.put("accept_partial", false);
            payload.put("description", "ReCover-AI recovery link for txn " + event.transactionId());
            payload.put("customer", customer);
            payload.put("notify", notify);
            payload.put("reference_id", event.transactionId());

            PaymentLink paymentLink = razorpayClient.paymentLink.create(payload);
            String shortUrl = paymentLink.get("short_url");

            log.info("Created Razorpay payment link for txn={} action={} -> {}",
                    event.transactionId(), decision.action(), shortUrl);
            return shortUrl;
        } catch (RazorpayException e) {
            log.error("Failed to create Razorpay payment link for txn={}: {}",
                    event.transactionId(), e.getMessage(), e);
            return null;
        } catch (ArithmeticException e) {
            log.error("Failed to convert amount to paise for txn={}: {}", event.transactionId(), e.getMessage());
            return null;
        }
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}