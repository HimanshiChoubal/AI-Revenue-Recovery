package com.razorpay.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Logs, once at startup, whether real Razorpay TEST MODE keys are
 * configured or whether the app is still running on placeholder values
 * (in which case RecoveryOrchestrationService's mock payment-link
 * fallback will be active for every WHATSAPP_LINK/VOICE_OUTREACH
 * transaction).
 */
@Component
public class RazorpayKeyStartupCheck implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RazorpayKeyStartupCheck.class);
    private static final String PLACEHOLDER_KEY_ID = "YOUR_RAZORPAY_KEY_ID";
    private static final String TEST_KEY_PREFIX = "rzp_test_";


    @Value("${razorpay.key.id:}")
    private String keyId;

    @Override
    public void run(String... args) {
        boolean isPlaceholder = keyId == null || keyId.isBlank() || keyId.equals(PLACEHOLDER_KEY_ID);
        boolean isLiveTestKey = !isPlaceholder && keyId.startsWith(TEST_KEY_PREFIX);

        if (isLiveTestKey) {
            log.info("✅ LIVE Razorpay test-mode keys detected (key id starts with '{}') — " +
                    "payment links will be created via the real Razorpay API.", TEST_KEY_PREFIX);
        } else if (isPlaceholder) {
            log.warn("⚠️  PLACEHOLDER keys detected (razorpay.key.id='{}') — mock link fallback " +
                    "active for every recovery attempt. Set real rzp_test_ keys in " +
                    "application.properties to exercise the live Razorpay API.", keyId);
        } else {
            log.warn("⚠️  razorpay.key.id ('{}') does not start with '{}' — this looks like neither " +
                    "the known placeholder nor a standard test-mode key. Verify it's a valid " +
                    "Razorpay key before assuming live calls will succeed.", keyId, TEST_KEY_PREFIX);
        }
    }

}