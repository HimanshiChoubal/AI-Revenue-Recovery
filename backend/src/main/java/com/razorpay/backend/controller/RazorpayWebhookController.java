package com.razorpay.backend.controller;

import com.razorpay.Utils;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.entity.RecoveryAudit;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Receives real (or hackathon-simulated) Razorpay webhook payloads.
 * Verifies the X-Razorpay-Signature HMAC — using the same
 * razorpay.key.secret / razorpay.webhook.secret configuration that backs
 * the RazorpayClient bean in RazorpayConfig — before processing anything.
 * Only {@code payment.failed} events are dispatched into the recovery
 * pipeline; everything else is acknowledged and ignored.
 */
@RestController
@RequestMapping("/api/v1/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);
    private static final BigDecimal PAISE_PER_RUPEE = BigDecimal.valueOf(100);
    private static final String PAYMENT_FAILED_EVENT = "payment.failed";
    private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";

    private final RecoveryOrchestrationService orchestrationService;
    private final String webhookSecret;

    public RazorpayWebhookController(
            RecoveryOrchestrationService orchestrationService,
            @Value("${razorpay.webhook.secret:}") String webhookSecret) {
        this.orchestrationService = orchestrationService;
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

            RecoveryAudit audit = orchestrationService.processFailure(event);

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

    /**
     * Verifies the HMAC-SHA256 webhook signature using Razorpay's own
     * Utils helper (the same SDK that RazorpayConfig wires up as the
     * RazorpayClient bean). If no webhook secret is configured
     * (local/dev demo), verification is skipped with a loud warning
     * rather than silently accepting everything in production.
     */
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
}