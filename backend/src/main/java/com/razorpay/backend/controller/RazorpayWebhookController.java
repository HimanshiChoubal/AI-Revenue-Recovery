package com.razorpay.backend.controller;

import com.razorpay.Utils;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.repository.RecoveryAuditRepository;
import com.razorpay.backend.service.RecoveryOrchestrationService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);
    private static final BigDecimal PAISE_PER_RUPEE = BigDecimal.valueOf(100);
    private static final String PAYMENT_FAILED_EVENT = "payment.failed";
    private static final String PAYMENT_LINK_PAID_EVENT = "payment_link.paid";
    private static final String STATUS_CONFIRMED_RECOVERED = "CONFIRMED_RECOVERED";
    private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";

    private final RecoveryOrchestrationService orchestrationService;
    private final RecoveryAuditRepository auditRepository;
    private final String webhookSecret;

    public RazorpayWebhookController(
            RecoveryOrchestrationService orchestrationService,
            RecoveryAuditRepository auditRepository,
            @Value("${razorpay.webhook.secret:}") String webhookSecret) {
        this.orchestrationService = orchestrationService;
        this.auditRepository = auditRepository;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        if (!isSignatureValid(rawPayload, signature)) {
            log.warn("Rejected webhook: missing or invalid {} header", SIGNATURE_HEADER);
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Invalid webhook signature"));
        }

        JSONObject payload;
        try {
            payload = new JSONObject(rawPayload);
        } catch (Exception e) {
            log.error("Failed to parse incoming Razorpay webhook payload", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Malformed JSON payload"));
        }

        String eventType = payload.optString("event", null);
        if (eventType == null) {
            log.warn("Webhook payload missing 'event' field; ignoring");
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "missing event field"));
        }

        if (PAYMENT_LINK_PAID_EVENT.equals(eventType)) {
            return handlePaymentLinkPaid(payload);
        }
        if (!PAYMENT_FAILED_EVENT.equals(eventType)) {
            log.debug("Ignoring Razorpay webhook event of type '{}'", eventType);
            return ResponseEntity.ok(Map.of("status", "ignored", "event", eventType));
        }

        try {
            JSONObject entity = payload
                    .optJSONObject("payload", new JSONObject())
                    .optJSONObject("payment", new JSONObject())
                    .optJSONObject("entity");

            if (entity == null) {
                log.warn("payment.failed webhook missing payload.payment.entity");
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Missing payload.payment.entity"));
            }

            String paymentId = entity.has("id") && !entity.isNull("id") ? entity.optString("id") : null;
            if (paymentId == null || paymentId.isBlank()) {
                log.warn("payment.failed webhook missing payment id");
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Missing payment id"));
            }

            long amountInPaise = entity.optLong("amount", 0L);
            String errorCode = entity.has("error_code") && !entity.isNull("error_code")
                    ? entity.optString("error_code")
                    : "UNKNOWN_ERROR";
            String contact = entity.has("contact") && !entity.isNull("contact")
                    ? entity.optString("contact")
                    : null;
            String email = entity.has("email") && !entity.isNull("email")
                    ? entity.optString("email")
                    : null;

            BigDecimal amountInRupees = BigDecimal.valueOf(amountInPaise)
                    .divide(PAISE_PER_RUPEE, 2, RoundingMode.HALF_UP);

            String customerName = (email != null && email.contains("@"))
                    ? email.substring(0, email.indexOf('@'))
                    : "Razorpay Customer";

            PaymentFailureEventDto event = new PaymentFailureEventDto(
                    paymentId,
                    amountInRupees,
                    errorCode,
                    customerName,
                    contact,
                    0
            );

            log.info("Received verified payment.failed webhook: payment_id={} amount=₹{} error_code={}",
                    paymentId, amountInRupees, errorCode);

            RecoveryAudit audit = orchestrationService.processFailure(event,true);

            return ResponseEntity.ok(Map.of(
                    "status", "processed",
                    "transaction_id", audit.getTransactionId(),
                    "action_taken", audit.getActionTaken(),
                    "recovery_status", audit.getStatus()
            ));
        } catch (Exception e) {
            log.error("Unexpected error while processing payment.failed webhook", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", String.valueOf(e.getMessage())));
        }
    }

    private ResponseEntity<Map<String, Object>> handlePaymentLinkPaid(JSONObject payload) {
        JSONObject entity = payload
                .optJSONObject("payload", new JSONObject())
                .optJSONObject("payment_link", new JSONObject())
                .optJSONObject("entity");

        if (entity == null) {
            log.warn("payment_link.paid webhook missing payload.payment_link.entity");
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Missing payload.payment_link.entity"));
        }

        String paymentLinkId = entity.has("id") && !entity.isNull("id") ? entity.optString("id") : null;

        if (paymentLinkId == null || paymentLinkId.isBlank()) {
            log.warn("payment_link.paid webhook missing entity.id (payment_link_id)");
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Missing payment_link_id"));
        }

        List<RecoveryAudit> matches = auditRepository.findByPaymentLinkId(paymentLinkId);
        if (matches.isEmpty()) {
            long rowsWithPaymentLinkId = auditRepository.countByPaymentLinkIdIsNotNull();
            log.warn("No RecoveryAudit row found for payment_link_id={} — {} rows currently have a non-null " +
                            "payment_link_id in recovery_audit. If that count is 0, this is a missing-data problem " +
                            "(payment_link_id was never persisted at link-creation time). If it's >0 but this specific " +
                            "ID isn't among them, this is a mismatch problem (wrong ID captured, or webhook firing for " +
                            "a link this app didn't create).",
                    paymentLinkId, rowsWithPaymentLinkId);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no matching audit row"));
        }

        RecoveryAudit audit = matches.get(0);
        audit.setStatus("CONFIRMED_RECOVERED");
        audit.setRecoveredAmount(audit.getAmount());
        auditRepository.save(audit);

        log.info("Confirmed recovery via payment_link.paid: txn={} payment_link_id={}",
                audit.getTransactionId(), paymentLinkId);

        return ResponseEntity.ok(Map.of(
                "status", "processed",
                "transaction_id", audit.getTransactionId(),
                "recovery_status", audit.getStatus()
        ));
    }

    private boolean isSignatureValid(String rawPayload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()
                || webhookSecret.equals("YOUR_RAZORPAY_WEBHOOK_SECRET")) {
            log.warn("razorpay.webhook.secret is not configured — skipping signature verification. " +
                    "Do not run like this in production.");
            return true;
        }

        if (signature == null || signature.isBlank()) {
            return false;
        }

        try {
            return Utils.verifyWebhookSignature(rawPayload, signature, webhookSecret);
        } catch (Exception e) {
            log.error("Webhook signature verification threw an exception", e);
            return false;
        }
    }
    @PostMapping("/dev/simulate-confirmation/{transactionId}")
    public ResponseEntity<Map<String, Object>> simulateConfirmation(@PathVariable String transactionId) {
        List<RecoveryAudit> matches = auditRepository.findByTransactionId(transactionId);

        if (matches.isEmpty()) {
            log.warn("simulate-confirmation: no RecoveryAudit row found for transaction_id={}", transactionId);
            return ResponseEntity.status(404)
                    .body(Map.of("status", "error", "message", "No transaction found with id " + transactionId));
        }

        RecoveryAudit audit = matches.get(0);
        audit.setStatus(STATUS_CONFIRMED_RECOVERED);
        audit.setRecoveredAmount(audit.getAmount());
        auditRepository.save(audit);

        log.warn("SIMULATED webhook confirmation for txn={} — not a real Razorpay event", transactionId);

        return ResponseEntity.ok(Map.of(
                "status", "processed",
                "transaction_id", audit.getTransactionId(),
                "recovery_status", audit.getStatus()
        ));
    }
}