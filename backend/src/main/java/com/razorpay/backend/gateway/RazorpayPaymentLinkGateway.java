package com.razorpay.backend.gateway;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * Production implementation of {@link PaymentLinkGateway}, backed by the
 * real Razorpay Java SDK.
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