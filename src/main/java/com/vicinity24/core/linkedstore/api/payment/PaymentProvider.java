package com.vicinity24.core.linkedstore.api.payment;

import com.vicinity24.core.linkedstore.api.exception.StripePaymentFailedException;

public interface PaymentProvider {

    String providerId();

    PaymentResponse capturePayment(PaymentRequest request) throws StripePaymentFailedException;

    PayoutResponse payout(PayoutRequest request) throws StripePaymentFailedException;
}
