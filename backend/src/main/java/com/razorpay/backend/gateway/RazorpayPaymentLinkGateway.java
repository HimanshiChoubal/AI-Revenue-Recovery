package com.razorpay.backend.gateway;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * Production implementation of {@link PaymentLinkGateway}, backed by the
 * real Razorpay Java SDK.
 *
 * Field compliance check against Razorpay's Payment Links API (verified
 * against the payload built in RecoveryOrchestrationService.
 * createRazorpayPaymentLink):
 *   - amount (required)        ✓ present, in paise
 *   - currency (required)      ✓ present, "INR"
 *   - description (optional)   ✓ present
 *   - customer.name            ✓ present
 *   - customer.contact         ✓ present
 *   - customer.email           ✗ NOT present — PaymentFailureEventDto has
 *                                 no email field, so email-channel outreach
 *                                 is structurally unavailable regardless of
 *                                 notify.email
 *   - notify.sms / notify.email ✓ present (sms=true, email=false)
 *   - reference_id (optional)  ✓ present, set to transaction_id
 *   - accept_partial (optional) ✓ present, false
 *
 * amount and currency are the only two fields Razorpay's API actually
 * rejects the request without; everything else here is optional and
 * already supplied where the domain model has the data.
 */
@Component
public class RazorpayPaymentLinkGateway implements PaymentLinkGateway {

    private final RazorpayClient razorpayClient;

    public RazorpayPaymentLinkGateway(RazorpayClient razorpayClient) {
        this.razorpayClient = razorpayClient;
    }

    @Override
    public String createPaymentLink(JSONObject payload) throws RazorpayException {
        PaymentLink paymentLink = razorpayClient.paymentLink.create(payload);
        return paymentLink.get("short_url");
    }
}