package com.razorpay.backend.controller;

import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.service.RecoveryOrchestrationService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Receives real (or hackathon-simulated) Razorpay webhook payloads.
 * Only {@code payment.failed} events are dispatched into the recovery
 * pipeline; everything else is acknowledged and ignored.
 */
@RestController
@RequestMapping("/api/v1/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);
    private static final BigDecimal PAISE_PER_RUPEE = BigDecimal.valueOf(100);
    private static final String PAYMENT_FAILED_EVENT = "payment.failed";

    private final RecoveryOrchestrationService orchestrationService;

    public RazorpayWebhookController(RecoveryOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(@RequestBody String rawPayload) {
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

            log.info("Received payment.failed webhook: payment_id={} amount=₹{} error_code={}",
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
}