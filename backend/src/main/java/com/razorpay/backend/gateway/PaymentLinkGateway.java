package com.razorpay.backend.gateway;

import com.razorpay.RazorpayException;
import org.json.JSONObject;

/**
 * Thin seam around the Razorpay SDK's payment link creation call.
 *
 * Depending on this interface (instead of RazorpayClient directly) keeps
 * RecoveryOrchestrationService's unit tests from having to mock a
 * third-party SDK class — RazorpayClient's nested client fields and
 * final/sealed types don't always play well with Mockito's inline
 * bytecode instrumentation across JVM versions. A plain interface mocks
 * cleanly everywhere.
 */
public interface PaymentLinkGateway {

    /**
     * Creates a payment link and returns its short (rzp.io) URL.
     *
     * @throws RazorpayException if the Razorpay API call fails
     */
    String createPaymentLink(JSONObject payload) throws RazorpayException;
}