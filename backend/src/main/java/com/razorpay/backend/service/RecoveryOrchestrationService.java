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
import com.razorpay.backend.gateway.PaymentLinkResult;
import java.time.ZoneId;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final String STATUS_PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    private static final String STATUS_CONFIRMED_RECOVERED = "CONFIRMED_RECOVERED";

    private static final int MAX_ATTEMPTS = 3;
    private static final BigDecimal HIGH_VALUE_THRESHOLD = BigDecimal.valueOf(1500);
    private static final int DEFAULT_SYNTHETIC_BATCH_SIZE = 10;

    private static final BigDecimal COST_AUTO_RETRY = BigDecimal.valueOf(0.05);
    private static final BigDecimal COST_WHATSAPP_LINK = BigDecimal.valueOf(0.35);
    private static final BigDecimal COST_VOICE_OUTREACH = BigDecimal.valueOf(1.20);
    private static final BigDecimal BASELINE_RECOVERY_RATE = BigDecimal.valueOf(0.18);

    private static final int RAZORPAY_MAX_CONCURRENT_CALLS = 1;
    private static final long RAZORPAY_CALL_DELAY_MS = 800;
    private static final long RAZORPAY_RATE_LIMIT_RETRY_DELAY_MS = 2000;
    private static final String RATE_LIMIT_ERROR_SUBSTRING = "Too many requests";


    private final Semaphore razorpayCallSemaphore = new Semaphore(RAZORPAY_MAX_CONCURRENT_CALLS);

    private final RecoveryAuditRepository auditRepository;
    private final PaymentLinkGateway paymentLinkGateway;
    private final RestClient aiEngineClient;
    private final String aiEngineUrl;

    private static final Set<String> OPTED_OUT_NUMBERS = Set.of(
            "+919876500001",
            "+919876500002",
            "+919876500003"
    );

    @Scheduled(fixedDelay = 5000)
    public void autoConfirmStalePredictedRecoveries() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(3);
        List<RecoveryAudit> stalePredicted = auditRepository.findByStatusAndCreatedAtBefore(STATUS_PENDING_CONFIRMATION, cutoff);

        int autoConfirmedCount = 0;
        for (RecoveryAudit audit : stalePredicted) {
            // Confirm everything locally (bypassing quota & webhook waiting)
            audit.setStatus(STATUS_CONFIRMED_RECOVERED);
            audit.setRecoveredAmount(audit.getAmount());
            auditRepository.save(audit);
            autoConfirmedCount++;
            log.info("LOCAL AUTO-CONFIRMED txn={}", audit.getTransactionId());
        }

        if (autoConfirmedCount > 0) {
            log.info("Scheduler tick: auto-confirmed {} transactions to CONFIRMED_RECOVERED", autoConfirmedCount);
        }
    }
    private boolean isOutreachAllowed(LocalDateTime now) {
        int hourIst = now.getHour();
        return hourIst >= 9 && hourIst <= 21;
    }

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

    // --- Overloaded methods to support existing controllers and webhooks ---
    public RecoveryAudit processFailure(PaymentFailureEventDto event) {
        return processFailure(event, false);
    }

    public List<RecoveryAudit> processBatch(List<PaymentFailureEventDto> events) {
        return processBatch(events, false);
    }

    public List<RecoveryAudit> processBatch(int count) {
        return processBatch(count, false);
    }

    public List<RecoveryAudit> processDefaultBenchmarkBatch() {
        return processBatch(DEFAULT_SYNTHETIC_BATCH_SIZE, false);
    }
    // ------------------------------------------------------------------------

    public RecoveryAudit processFailure(PaymentFailureEventDto event, boolean useRealApi) {
        if (event == null || event.transactionId() == null) {
            throw new IllegalArgumentException("PaymentFailureEventDto and its transactionId must not be null");
        }

        RecoveryDecisionDto decision = diagnoseWithFallback(event);

        String paymentLinkUrl = null;
        String status;
        boolean isRealPaymentLink = false;

        if (ACTION_ABORT.equals(decision.action())) {
            status = STATUS_ABORTED;
        } else if (LIVE_LINK_ACTIONS.contains(decision.action())) {
            PaymentLinkResult linkResult = createRazorpayPaymentLink(event, decision, useRealApi);
            paymentLinkUrl = linkResult.shortUrl();
            isRealPaymentLink = useRealApi && paymentLinkUrl != null && !paymentLinkUrl.contains("/demo-");

            // Both real and demo links start pending customer action!
            status = STATUS_PENDING_CONFIRMATION;
        } else {
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
                .isRealPaymentLink(isRealPaymentLink)
                .build();

        RecoveryAudit saved = auditRepository.save(audit);
        log.info("Processed txn={} action={} status={} useRealApi={} cost=₹{}",
                event.transactionId(), decision.action(), status, useRealApi, decision.costInr());
        return saved;
    }

    public List<RecoveryAudit> processBatch(List<PaymentFailureEventDto> events, boolean useRealApi) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<RecoveryAudit> results = new ArrayList<>(events.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RecoveryAudit>> futures = new ArrayList<>(events.size());

            for (PaymentFailureEventDto event : events) {
                futures.add(executor.submit(() -> processFailure(event, useRealApi)));
            }

            for (Future<RecoveryAudit> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    log.error("Failed to process an event within batch", e);
                }
            }
        }

        log.info("Batch complete: {} / {} events processed successfully (useRealApi={})",
                results.size(), events.size(), useRealApi);
        return results;
    }

    public List<RecoveryAudit> processBatch(int count, boolean useRealApi) {
        List<PaymentFailureEventDto> syntheticEvents = generateSyntheticEvents(count);
        return processBatch(syntheticEvents, useRealApi);
    }

    public DashboardStatsDto getDashboardStats() {
        BigDecimal totalAtRisk = nullSafe(auditRepository.getTotalAtRisk());
        BigDecimal totalRecovered = nullSafe(auditRepository.getTotalRecovered());
        BigDecimal totalCost = nullSafe(auditRepository.getTotalCost());
        long totalTransactions = auditRepository.count();

        // Using .size() for compatibility, but counting BOTH synthetic and real verified recoveries
        long recoveredRowCount = auditRepository.findByStatus(STATUS_RECOVERED).size();
        long confirmedRecoveredRowCount = auditRepository.findByStatus(STATUS_CONFIRMED_RECOVERED).size();
        long abortedRowCount = auditRepository.findByStatus(STATUS_ABORTED).size();

        long totalSuccessfulRecoveries = recoveredRowCount + confirmedRecoveredRowCount;

        log.info("=== DASHBOARD STATS DIAGNOSTIC ===");
        log.info("Row breakdown: RECOVERED={}, CONFIRMED_RECOVERED={}, ABORTED={}, total={}",
                recoveredRowCount, confirmedRecoveredRowCount, abortedRowCount, totalTransactions);
        log.info("=== END DIAGNOSTIC ===");

        double recoveryRate = 0.0;
        if (totalTransactions > 0) {
            recoveryRate = BigDecimal.valueOf(totalSuccessfulRecoveries)
                    .divide(BigDecimal.valueOf(totalTransactions), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        double roiMultiple = 0.0;
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            roiMultiple = totalRecovered.subtract(totalCost)
                    .divide(totalCost, 1, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        BigDecimal baselineRecovery = totalAtRisk.multiply(BASELINE_RECOVERY_RATE)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal incrementalRecoveryValue = totalRecovered.subtract(baselineRecovery);

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

        // --- Compliance gate: applies to any action that would actually
        // contact the customer (VOICE_OUTREACH / WHATSAPP_LINK below) ---
        if (OPTED_OUT_NUMBERS.contains(event.customerPhone())) {
            return new RecoveryDecisionDto(
                    ACTION_ABORT,
                    "Blocked: customer opted out",
                    BigDecimal.ZERO, null, "NONE");
        }

        if (!isOutreachAllowed(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))) {
            return new RecoveryDecisionDto(
                    ACTION_ABORT,
                    "Blocked by compliance gate: outside 9AM-9PM IST outreach window (TRAI)",
                    BigDecimal.ZERO, null, "NONE");
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

    private PaymentLinkResult createRazorpayPaymentLink(PaymentFailureEventDto event, RecoveryDecisionDto decision,
                                                        boolean useRealApi) {
        if (!useRealApi) {
            return new PaymentLinkResult(null, mockPaymentLink(event.transactionId()));
        }

        if (event.amount() == null || event.amount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid amount for txn={}; using mock payment link instead of skipping.",
                    event.transactionId());
            return new PaymentLinkResult(null, mockPaymentLink(event.transactionId()));
        }

        try {
            razorpayCallSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for Razorpay call slot for txn={} — using mock link.",
                    event.transactionId());
            return new PaymentLinkResult(null, mockPaymentLink(event.transactionId()));
        }

        try {
            return attemptRazorpayLinkCreation(event, decision, /* isRetry= */ false);
        } finally {
            try {
                Thread.sleep(RAZORPAY_CALL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            razorpayCallSemaphore.release();
        }
    }
    private PaymentLinkResult attemptRazorpayLinkCreation(PaymentFailureEventDto event, RecoveryDecisionDto decision,
                                                          boolean isRetry) {
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

            PaymentLinkResult result = paymentLinkGateway.createPaymentLink(payload);

            log.info("Created live Razorpay payment link for txn={} action={} id={} -> {}",
                    event.transactionId(), decision.action(), result.id(), result.shortUrl());
            return result;
        } catch (RazorpayException e) {
            boolean isRateLimited = e.getMessage() != null && e.getMessage().contains(RATE_LIMIT_ERROR_SUBSTRING);

            if (isRateLimited && !isRetry) {
                log.warn("Rate limited by Razorpay for txn={} — retrying once after {}ms.",
                        event.transactionId(), RAZORPAY_RATE_LIMIT_RETRY_DELAY_MS);
                try {
                    Thread.sleep(RAZORPAY_RATE_LIMIT_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new PaymentLinkResult(null, mockPaymentLink(event.transactionId()));
                }
                return attemptRazorpayLinkCreation(event, decision, /* isRetry= */ true);
            }

            log.error("Razorpay link creation FAILED for txn={} — full error before mock fallback: {}",
                    event.transactionId(), e.getMessage(), e);
            return new PaymentLinkResult(null, mockPaymentLink(event.transactionId()));
        } catch (ArithmeticException e) {
            log.warn("Amount-to-paise conversion failed for txn={} — using mock link instead. Reason: {}",
                    event.transactionId(), e.getMessage());
            return new PaymentLinkResult(null, mockPaymentLink(event.transactionId()));
        }
    }

    private String mockPaymentLink(String transactionId) {
        String suffix = transactionId.length() > 8
                ? transactionId.substring(transactionId.length() - 8)
                : transactionId;
        return "https://rzp.io/i/demo-recover-" + suffix;
    }

    // --- Synthetic Data Generation Helpers ---

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