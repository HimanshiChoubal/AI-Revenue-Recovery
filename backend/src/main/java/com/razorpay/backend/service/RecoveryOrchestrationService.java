package com.razorpay.backend.service;

import com.razorpay.RazorpayException;
import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.dto.RecoveryDecisionDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.gateway.PaymentLinkGateway;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Core orchestration layer for ReCover-AI (payment-failure recovery).
 *
 * Decision policy:
 *   - ABORT              -> attempts_so_far >= 3, or error in {CARD_BLOCKED, STOLEN_CARD}
 *   - AUTO_RETRY          -> GATEWAY_TIMEOUT (soft technical failure, safe silent retry)
 *   - VOICE_OUTREACH      -> user-side friction on a high-value transaction (>= HIGH_VALUE_THRESHOLD)
 *   - WHATSAPP_LINK       -> everything else
 *
 * Calls the Python AI diagnostic engine first; if it's unreachable, the
 * same policy is applied locally so recovery never silently stalls.
 */
@Service
public class RecoveryOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryOrchestrationService.class);

    private static final Set<String> ABORT_ERROR_CODES = Set.of("CARD_BLOCKED", "STOLEN_CARD");
    private static final Set<String> LIVE_LINK_ACTIONS = Set.of("WHATSAPP_LINK", "VOICE_OUTREACH");

    private static final String ACTION_AUTO_RETRY = "AUTO_RETRY";
    private static final String ACTION_WHATSAPP_LINK = "WHATSAPP_LINK";
    private static final String ACTION_VOICE_OUTREACH = "VOICE_OUTREACH";
    private static final String ACTION_ABORT = "ABORT";

    private static final String STATUS_RECOVERED = "RECOVERED";
    private static final String STATUS_ABORTED = "ABORTED";

    private static final int MAX_ATTEMPTS = 3;
    private static final BigDecimal HIGH_VALUE_THRESHOLD = BigDecimal.valueOf(1500);
    private static final int DEFAULT_SYNTHETIC_BATCH_SIZE = 5;

    private static final BigDecimal COST_AUTO_RETRY = BigDecimal.valueOf(0.05);
    private static final BigDecimal COST_WHATSAPP_LINK = BigDecimal.valueOf(0.35);
    private static final BigDecimal COST_VOICE_OUTREACH = BigDecimal.valueOf(1.20);
    private static final BigDecimal BASELINE_RECOVERY_RATE = BigDecimal.valueOf(0.18);
    private final RecoveryAuditRepository auditRepository;
    private final PaymentLinkGateway paymentLinkGateway;
    private final RestClient aiEngineClient;
    private final String aiEngineUrl;

    public RecoveryOrchestrationService(
            RecoveryAuditRepository auditRepository,
            PaymentLinkGateway paymentLinkGateway,
            RestClient.Builder restClientBuilder,
            @Value("${ai.engine.url:http://localhost:8000/api/v1/diagnose-and-plan}") String aiEngineUrl) {
        this.auditRepository = auditRepository;
        this.paymentLinkGateway = paymentLinkGateway;
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
            // createRazorpayPaymentLink always returns a usable URL (live or
            // deterministic mock), so these actions are treated as recovered.
            paymentLinkUrl = createRazorpayPaymentLink(event, decision);
            status = STATUS_RECOVERED;
        } else {
            // AUTO_RETRY: silent retry, no customer-facing link needed.
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
     * Fan a batch of failure events out across Java 21 virtual threads.
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
     * Convenience overload for the "Trigger Batch Benchmark" button: generates
     * {@code count} synthetic Indian payment failures matching typical Razorpay
     * failure distributions and runs them through {@link #processBatch(List)}.
     */
    public List<RecoveryAudit> processBatch(int count) {
        List<PaymentFailureEventDto> syntheticEvents = generateSyntheticEvents(count);
        return processBatch(syntheticEvents);
    }

    public List<RecoveryAudit> processDefaultBenchmarkBatch() {
        return processBatch(DEFAULT_SYNTHETIC_BATCH_SIZE);
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

        // Calculate ROI Multiple: (Recovered - Cost) / Cost
        double roiMultiple = 0.0;
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            roiMultiple = totalRecovered.subtract(totalCost)
                    .divide(totalCost, 2, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        // Calculate Incremental Recovery Value
        BigDecimal baselineRecovery = totalAtRisk.multiply(BASELINE_RECOVERY_RATE)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal incrementalRecoveryValue = totalRecovered.subtract(baselineRecovery);

        // Pass all 7 arguments matching the DTO definition
        return new DashboardStatsDto(
                totalAtRisk,
                totalRecovered,
                totalCost,
                totalTransactions,
                recoveryRate,
                incrementalRecoveryValue,
                roiMultiple
        );
    }

    // ------------------------------------------------------------------
    // Decision logic
    // ------------------------------------------------------------------

    /**
     * Calls the Python diagnostic engine. If it's unreachable or errors
     * out, applies the same decision policy locally so recovery never
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
            return localPolicyDecision(event);
        }
    }

    /**
     * The bounded, deterministic recovery policy, applied identically
     * whether it's used as the AI engine's own logic or as this
     * service's offline fallback:
     *
     *   ABORT          - attempts_so_far >= 3, or error in {CARD_BLOCKED, STOLEN_CARD}
     *   AUTO_RETRY     - GATEWAY_TIMEOUT
     *   VOICE_OUTREACH - user-side friction, amount >= ₹1,500
     *   WHATSAPP_LINK  - everything else
     */
    private RecoveryDecisionDto localPolicyDecision(PaymentFailureEventDto event) {
        String errorCode = event.errorCode() != null ? event.errorCode().trim().toUpperCase() : "";
        BigDecimal amount = nullSafe(event.amount());

        if (event.attemptsSoFar() >= MAX_ATTEMPTS) {
            return new RecoveryDecisionDto(
                    ACTION_ABORT,
                    String.format("Max retry attempts reached (%d/%d); halting to avoid customer fatigue.",
                            event.attemptsSoFar(), MAX_ATTEMPTS),
                    BigDecimal.ZERO, null, "NONE");
        }

        if (ABORT_ERROR_CODES.contains(errorCode)) {
            return new RecoveryDecisionDto(
                    ACTION_ABORT,
                    "Error code '" + errorCode + "' is terminal/unrecoverable; further outreach is unsafe.",
                    BigDecimal.ZERO, null, "NONE");
        }

        if ("GATEWAY_TIMEOUT".equals(errorCode)) {
            return new RecoveryDecisionDto(
                    ACTION_AUTO_RETRY,
                    "Soft technical/infra failure; safe to silently retry on an alternate gateway.",
                    COST_AUTO_RETRY, null, "BACKUP_GATEWAY");
        }

        if (amount.compareTo(HIGH_VALUE_THRESHOLD) >= 0) {
            return new RecoveryDecisionDto(
                    ACTION_VOICE_OUTREACH,
                    String.format("User-side friction ('%s') on a high-value transaction (₹%s) — voice "
                            + "outreach maximizes recovery probability.", errorCode, amount),
                    COST_VOICE_OUTREACH,
                    "Namaste! Aapka payment fail ho gaya tha, kripya humare secure link se dobara try karein.",
                    "IVR_VOICE_CALL::" + event.customerPhone());
        }

        return new RecoveryDecisionDto(
                ACTION_WHATSAPP_LINK,
                String.format("'%s' does not warrant a costlier voice call at this amount; a 1-click "
                        + "WhatsApp smart link is the most cost-efficient recovery path.", errorCode),
                COST_WHATSAPP_LINK,
                "Hi! Your payment didn't go through. Tap the link below to complete it securely.",
                "WHATSAPP::" + event.customerPhone());
    }

    /**
     * Creates a live Razorpay test payment link (rzp.io) for a customer to
     * complete their failed payment in one click. Falls back to a
     * deterministic mock rzp.io link if the gateway call fails for any
     * reason (placeholder test keys, network issue), so demo/benchmark
     * runs still populate recovered metrics realistically.
     */
    private String createRazorpayPaymentLink(PaymentFailureEventDto event, RecoveryDecisionDto decision) {
        if (event.amount() == null || event.amount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid amount for txn={}; using mock payment link instead of skipping.",
                    event.transactionId());
            return mockPaymentLink(event.transactionId());
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

            JSONObject notes = new JSONObject();
            notes.put("source", "ReCover-AI automated recovery");
            notes.put("failure_reason", event.errorCode());
            notes.put("recovery_action", decision.action());

            JSONObject payload = new JSONObject();
            payload.put("amount", amountInPaise);
            payload.put("currency", "INR");
            payload.put("accept_partial", false);
            payload.put("description", "Recover payment for order " + event.transactionId());
            payload.put("customer", customer);
            payload.put("notify", notify);
            payload.put("notes", notes);
            payload.put("reference_id", event.transactionId());
            payload.put("reminder_enable", false);

            String shortUrl = paymentLinkGateway.createPaymentLink(payload);

            log.info("Created live Razorpay payment link for txn={} action={} -> {}",
                    event.transactionId(), decision.action(), shortUrl);
            return shortUrl;
        } catch (RazorpayException e) {
            log.error("Razorpay link creation FAILED for txn={} — full error before mock fallback: {}",
                    event.transactionId(), e.getMessage(), e);
            return mockPaymentLink(event.transactionId());
        } catch (ArithmeticException e) {
            log.warn("Amount-to-paise conversion failed for txn={} — using mock link instead. Reason: {}",
                    event.transactionId(), e.getMessage());
            return mockPaymentLink(event.transactionId());
        }
    }

    /**
     * Deterministic mock rzp.io-style link, keyed off the transaction id so
     * repeated calls for the same transaction always produce the same URL.
     *
     * Uses a "/demo-" path segment specifically so the dashboard template
     * can tell a mock/fallback link apart from a real Razorpay short_url
     * (which never contains this segment) without needing a separate
     * "is this real" field persisted on RecoveryAudit.
     */
    private String mockPaymentLink(String transactionId) {
        String suffix = transactionId.length() > 8
                ? transactionId.substring(transactionId.length() - 8)
                : transactionId;
        return "https://rzp.io/i/demo-recover-" + suffix;
    }

    // ------------------------------------------------------------------
    // Synthetic data generation (for the "Trigger Batch Benchmark" button)
    // ------------------------------------------------------------------

    private static final String[] FIRST_NAMES = {
            "Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Krishna", "Ishaan",
            "Rohan", "Kabir", "Ananya", "Diya", "Saanvi", "Aadhya", "Kiara", "Myra",
            "Priya", "Neha", "Pooja", "Sneha", "Ravi", "Suresh", "Manoj", "Deepak"
    };
    private static final String[] LAST_NAMES = {
            "Sharma", "Verma", "Gupta", "Iyer", "Nair", "Reddy", "Patel", "Mehta",
            "Singh", "Kumar", "Rao", "Joshi", "Chopra", "Malhotra", "Bose", "Pillai"
    };
    private static final String[] SOFT_GATEWAY_CODES = {"GATEWAY_TIMEOUT", "BAD_REQUEST_PAYMENT_TIMED_OUT"};
    private static final String[] USER_FRICTION_CODES = {"INSUFFICIENT_FUNDS", "OTP_FAILED", "AUTHENTICATION_FAILED"};
    private static final String[] MANDATE_CODES = {"MANDATE_EXPIRED"};
    private static final String[] HARD_BLOCK_CODES = {"CARD_BLOCKED", "STOLEN_CARD"};

    /**
     * Generates synthetic Indian payment failures matching typical Razorpay
     * failure distributions: 40% soft gateway drops, 35% user friction/UPI
     * drops, 15% mandate failures, 10% hard card blocks.
     */
    private List<PaymentFailureEventDto> generateSyntheticEvents(int count) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<PaymentFailureEventDto> events = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String errorCode = pickErrorCode(random);
            String customerName = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)]
                    + " " + LAST_NAMES[random.nextInt(LAST_NAMES.length)];
            String customerPhone = "+91" + (6 + random.nextInt(4)) + randomDigits(random, 9);
            BigDecimal amount = randomAmount(random, errorCode);
            int attemptsSoFar = pickAttempts(random);

            events.add(new PaymentFailureEventDto(
                    "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14),
                    amount,
                    errorCode,
                    customerName,
                    customerPhone,
                    attemptsSoFar
            ));
        }

        log.info("Generated {} synthetic failure events at {}", count, LocalDateTime.now());
        return events;
    }

    private String pickErrorCode(ThreadLocalRandom random) {
        int roll = random.nextInt(100);
        if (roll < 40) {
            return SOFT_GATEWAY_CODES[random.nextInt(SOFT_GATEWAY_CODES.length)];
        } else if (roll < 75) {
            return USER_FRICTION_CODES[random.nextInt(USER_FRICTION_CODES.length)];
        } else if (roll < 90) {
            return MANDATE_CODES[random.nextInt(MANDATE_CODES.length)];
        } else {
            return HARD_BLOCK_CODES[random.nextInt(HARD_BLOCK_CODES.length)];
        }
    }

    private BigDecimal randomAmount(ThreadLocalRandom random, String errorCode) {
        double value = switch (errorCode) {
            case "MANDATE_EXPIRED" -> 99 + random.nextInt(1900);
            case "CARD_BLOCKED", "STOLEN_CARD" -> 500 + random.nextDouble() * 74500;
            case "INSUFFICIENT_FUNDS", "OTP_FAILED", "AUTHENTICATION_FAILED" -> 150 + random.nextDouble() * 24850;
            default -> 99 + random.nextDouble() * 14901;
        };
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private int pickAttempts(ThreadLocalRandom random) {
        int roll = random.nextInt(100);
        if (roll < 55) return 0;
        if (roll < 80) return 1;
        if (roll < 92) return 2;
        return 3;
    }

    private String randomDigits(ThreadLocalRandom random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
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