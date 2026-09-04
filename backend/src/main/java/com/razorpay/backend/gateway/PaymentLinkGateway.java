package com.razorpay.backend.gateway;

import com.razorpay.RazorpayException;
import org.json.JSONObject;

public interface PaymentLinkGateway {

    PaymentLinkResult createPaymentLink(JSONObject payload) throws RazorpayException;
}