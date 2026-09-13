package com.vicinity24.core.linkedstore.api.payment.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.exception.StripePaymentFailedException;
import com.vicinity24.core.linkedstore.api.payment.PayoutRequest;
import com.vicinity24.core.linkedstore.api.payment.PayoutResponse;
import com.vicinity24.core.linkedstore.api.payment.PaymentProvider;
import com.vicinity24.core.linkedstore.api.payment.PaymentProviderFactory;
import com.vicinity24.core.linkedstore.api.payment.PaymentRequest;
import com.vicinity24.core.linkedstore.api.payment.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripePaymentProvider implements PaymentProvider {

    private final StripeConfig stripeConfig;

    @Override
    public String providerId() { return PaymentProviderFactory.PROVIDER_STRIPE; }

    @Override
    public PaymentResponse capturePayment(PaymentRequest request) {
        try {
            PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                    .setAmount(request.getAmountCents())
                    .setCurrency(request.getCurrency() == null ? "usd" : request.getCurrency())
                    .setDescription(request.getDescription())
                    .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                    .setConfirm(request.isConfirm())
                    .addPaymentMethodType("card");

            if (request.getTransferGroup() != null && !request.getTransferGroup().isBlank()) {
                params.setTransferGroup(request.getTransferGroup());
            }
            if (request.getPaymentMethodId() != null && !request.getPaymentMethodId().isBlank()) {
                params.setPaymentMethod(request.getPaymentMethodId());
            }
            if (request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                params.setReceiptEmail(request.getCustomerEmail());
            }
            if (request.getCustomerId() != null && !request.getCustomerId().isBlank()) {
                params.setCustomer(request.getCustomerId());
            }
            if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
                request.getMetadata().forEach(params::putMetadata);
            }

            PaymentIntent pi;
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                pi = PaymentIntent.create(params.build(), com.stripe.net.RequestOptions.builder()
                        .setIdempotencyKey(request.getIdempotencyKey())
                        .build());
            } else {
                pi = PaymentIntent.create(params.build());
            }

            OffsetDateTime createdAt = toOffsetDateTime(pi.getCreated());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("status", pi.getStatus());
            raw.put("capture_method", pi.getCaptureMethod());
            raw.put("receipt_email", pi.getReceiptEmail());

            return PaymentResponse.builder()
                    .provider(providerId())
                    .paymentIntentId(pi.getId())
                    .clientSecret(pi.getClientSecret())
                    .amountCents(pi.getAmount())
                    .currency(pi.getCurrency())
                    .status(pi.getStatus())
                    .transferGroup(request.getTransferGroup())
                    .createdAt(createdAt)
                    .raw(raw)
                    .build();
        } catch (StripeException e) {
            log.warn("Stripe capture failed: code={} msg={}", e.getCode(), e.getMessage());
            throw new StripePaymentFailedException("Stripe declined the payment: " + e.getMessage(), e);
        }
    }

    @Override
    public PayoutResponse payout(PayoutRequest request) {
        if (request.getDestinationAccountId() == null || request.getDestinationAccountId().isBlank()) {
            throw new StripePaymentFailedException(
                    "Missing destination Stripe Connect account_id for payout", null);
        }
        try {
            TransferCreateParams.Builder params = TransferCreateParams.builder()
                    .setAmount(request.getAmountCents())
                    .setCurrency(request.getCurrency() == null ? "usd" : request.getCurrency())
                    .setDestination(request.getDestinationAccountId());
            if (request.getTransferGroup() != null && !request.getTransferGroup().isBlank()) {
                params.setTransferGroup(request.getTransferGroup());
            }
            if (request.getRoute() != null && !request.getRoute().isBlank()) {
                params.putMetadata("route", request.getRoute());
            }
            Transfer tx = Transfer.create(params.build());
            String status = "pending";
            if (tx.getId() != null && !tx.getId().isEmpty()) {
                status = "paid";
            }
            log.info("Stripe payout ok: id={} amount={}c dest={} group={}",
                    tx.getId(), tx.getAmount(), request.getDestinationAccountId(), request.getTransferGroup());
            return PayoutResponse.builder()
                    .provider(providerId())
                    .payoutId(tx.getId())
                    .amountCents(tx.getAmount())
                    .currency(tx.getCurrency())
                    .status(status)
                    .destinationAccountId(request.getDestinationAccountId())
                    .build();
        } catch (StripeException e) {
            log.warn("Stripe payout failed: code={} msg={}", e.getCode(), e.getMessage());
            throw new StripePaymentFailedException("Stripe payout failed: " + e.getMessage(), e);
        }
    }

    private static OffsetDateTime toOffsetDateTime(Long createdEpochSec) {
        if (createdEpochSec == null) return OffsetDateTime.now();
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(createdEpochSec), ZoneOffset.UTC);
    }
}
