package com.razorpay.backend.gateway;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;


@Component
public class RazorpayPaymentLinkGateway implements PaymentLinkGateway {

    private final RazorpayClient razorpayClient;

    public RazorpayPaymentLinkGateway(RazorpayClient razorpayClient) {
        this.razorpayClient = razorpayClient;
    }

    @Override
    public PaymentLinkResult createPaymentLink(JSONObject payload) throws RazorpayException {
        PaymentLink paymentLink = razorpayClient.paymentLink.create(payload);
        String id = paymentLink.get("id");
        String shortUrl = paymentLink.get("short_url");
        return new PaymentLinkResult(id, shortUrl);
    }
}