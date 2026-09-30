package com.vicinity24.core.linkedstore.subscription.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.vicinity24.core.linkedstore.api.config.BrandProperties;
import com.vicinity24.core.linkedstore.api.config.StripeConfig;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Subscription;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.subscription.PlanTier;
import com.vicinity24.core.linkedstore.subscription.SubscriptionTierSettings;
import com.vicinity24.core.linkedstore.subscription.dto.SubscriptionCheckoutRequest;
import com.vicinity24.core.linkedstore.subscription.dto.SubscriptionCheckoutResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionCheckoutService {

    private final SubscriptionTierSettings tierSettings;
    private final StoreRepository storeRepository;
    private final StripeConfig stripeConfig;
    private final BrandProperties brandProperties;
    private final AuthenticationFacade authenticationFacade;

    @Transactional
    public SubscriptionCheckoutResponse createCheckout(@Valid SubscriptionCheckoutRequest request) {
        authenticationFacade.requireCanManageSubscription(request.getStoreId());

        UUID storeId = request.getStoreId();
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("Store not found: " + storeId));

        PlanTier tier = parseTier(request.getPlanCode());
        String requestedPlanCode = tier.getPlanCode();
        String interval = normalizeInterval(request.getInterval());

        // Idempotency: if the store is already subscribed to the requested plan
        // (active, trialing, or even past-due but not yet canceled/expired),
        // do NOT create a duplicate Stripe subscription or checkout session.
        Subscription existing = store.getActiveSubscription();
        SubscriptionStatus storeStatus = store.getSubscriptionStatus();
        boolean storeColActive = storeStatus == SubscriptionStatus.ACTIVE
                || storeStatus == SubscriptionStatus.TRIALING
                || storeStatus == SubscriptionStatus.PAST_DUE;
        if (existing != null) {
            boolean samePlan = existing.getPlan() != null
                    && requestedPlanCode.equalsIgnoreCase(existing.getPlan().getPlanCode());
            boolean activeStatus = existing.getStatus() == SubscriptionStatus.ACTIVE
                    || existing.getStatus() == SubscriptionStatus.TRIALING
                    || existing.getStatus() == SubscriptionStatus.PAST_DUE
                    || (Boolean.TRUE.equals(existing.getCancelAtPeriodEnd()));
            if (samePlan && activeStatus) {
                log.info("Checkout skipped: store={} already subscribed to plan={}", storeId, requestedPlanCode);
                return SubscriptionCheckoutResponse.builder()
                        .type("ERROR")
                        .storeId(storeId.toString())
                        .planCode(requestedPlanCode)
                        .interval(interval)
                        .message("This store is already subscribed to " + tier.getDisplayName() + " Plan. Manage it from your store dashboard Subscription tab.")
                        .pricingPageUrl("/pricing")
                        .build();
            }
        } else if (storeColActive) {
            // Fallback via Store.subscription_status column (subscription row may be missing)
            return SubscriptionCheckoutResponse.builder()
                    .type("ERROR")
                    .storeId(storeId.toString())
                    .planCode(requestedPlanCode)
                    .interval(interval)
                    .message("This store is already subscribed to " + tier.getDisplayName() + " Plan. Manage it from your store dashboard Subscription tab.")
                    .pricingPageUrl("/pricing")
                    .build();
        }

        boolean isGlobalAdmin =
                authenticationFacade.current() != null
                        && authenticationFacade.current().isAuthenticated()
                        && authenticationFacade.current().isGlobalAdmin();

        if (tier == PlanTier.CUSTOM) {
            return SubscriptionCheckoutResponse.builder()
                    .type("CONTACT_SALES")
                    .storeId(storeId.toString())
                    .planCode(tier.getPlanCode())
                    .interval(interval)
                    .message("Custom Enterprise plan requires a dedicated sales onboarding.")
                    .contactSalesEmail(tierSettings.getCustom().getContactSalesEmail() == null
                            ? "sales@vicinity24.dev"
                            : tierSettings.getCustom().getContactSalesEmail())
                    .pricingPageUrl("/pricing")
                    .build();
        }

        ensureStripeKey();

        SubscriptionTierSettings.TierProSettings pro = tierSettings.getPro();
        long unitAmountCents;
        String sessionInterval;
        int intervalCount;
        if ("yearly".equalsIgnoreCase(interval) || "annual".equalsIgnoreCase(interval) || "year".equalsIgnoreCase(interval)) {
            unitAmountCents = (long) pro.getAnnualPriceCents();
            sessionInterval = "year";
            intervalCount = 1;
        } else {
            unitAmountCents = (long) pro.getMonthlyPriceCents();
            sessionInterval = "month";
            intervalCount = 1;
        }

        String currency = (pro.getCurrency() != null && !pro.getCurrency().isBlank()) ? pro.getCurrency() : "usd";
        int trialDays = pro.getTrialDays();

        String productName = brandProperties.getDisplayName() + " — " + tier.getDisplayName() + " Plan";
        String productDescription = tier == PlanTier.PRO
                ? "Unlimited stores, up to " + pro.getMonthlyOrderLimit() + " orders/mo, API & webhooks, email support."
                : "Platform subscription.";

        String successFallback = isGlobalAdmin
                ? "/admin?subscription=success&store=" + storeId
                : "/admin/stores/" + storeId + "/products?subscription=success&store=" + storeId;
        String cancelFallback = isGlobalAdmin
                ? "/admin?subscription=canceled&store=" + storeId
                : "/admin/stores/" + storeId + "/products?subscription=canceled&store=" + storeId;
        String successUrl = resolveUrl(request.getSuccessUrl(), successFallback);
        String cancelUrl = resolveUrl(request.getCancelUrl(), cancelFallback);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("storeId", storeId.toString());
        metadata.put("planCode", tier.getPlanCode());
        metadata.put("interval", interval);
        metadata.put("tier", tier.name());
        metadata.put("storeName", store.getBusinessName());
        metadata.put("billingInterval", sessionInterval);

        SessionCreateParams.LineItem lineItem = SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(productName)
                                .setDescription(productDescription)
                                .putAllMetadata(Map.of(
                                        "planCode", tier.getPlanCode(),
                                        "tier", tier.name(),
                                        "interval", sessionInterval
                                ))
                                .build())
                        .setUnitAmount(unitAmountCents)
                        .setRecurring(SessionCreateParams.LineItem.PriceData.Recurring.builder()
                                .setInterval(SessionCreateParams.LineItem.PriceData.Recurring.Interval.valueOf(sessionInterval.toUpperCase()))
                                .setIntervalCount((long) intervalCount)
                                .build())
                        .build())
                .build();

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setClientReferenceId("store:" + storeId)
                .addLineItem(lineItem)
                .setAllowPromotionCodes(true)
                .putAllMetadata(metadata)
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .setTrialPeriodDays(trialDays > 0 ? (long) trialDays : null)
                        .setDescription(productName + " (" + interval + ")")
                        .putAllMetadata(metadata)
                        .build())
                .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                .setPaymentMethodCollection(SessionCreateParams.PaymentMethodCollection.ALWAYS);

        try {
            Session session = Session.create(params.build());
            log.info("Subscription Checkout: created session={} store={} plan={} interval={} amountCents={} currency={} trialDays={}",
                    session.getId(), storeId, tier.getPlanCode(), interval, unitAmountCents, currency, trialDays);
            return SubscriptionCheckoutResponse.builder()
                    .type("CHECKOUT")
                    .sessionId(session.getId())
                    .url(session.getUrl())
                    .storeId(storeId.toString())
                    .planCode(tier.getPlanCode())
                    .interval(interval)
                    .message("Redirecting to Stripe Checkout…")
                    .build();
        } catch (StripeException sx) {
            String code = sx.getCode();
            String msg = sx.getUserMessage() != null ? sx.getUserMessage() : sx.getMessage();
            log.warn("Subscription Checkout failed Stripe call: store={} plan={} code={} msg={}",
                    storeId, tier.getPlanCode(), code, msg, sx);
            throw new IllegalStateException("Stripe could not start subscription session: "
                    + (msg != null ? msg : "code=" + code), sx);
        }
    }

    private PlanTier parseTier(String raw) {
        if (raw == null || raw.isBlank()) return PlanTier.PRO;
        String normalized = raw.trim().toUpperCase();
        if (normalized.equals("ENTERPRISE")) normalized = "CUSTOM";
        if (normalized.equals("PLUS") || normalized.equals("STANDARD")) normalized = "PRO";
        try {
            return PlanTier.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return PlanTier.PRO;
        }
    }

    private String normalizeInterval(String raw) {
        if (raw == null || raw.isBlank()) return "monthly";
        String s = raw.trim().toLowerCase();
        if (s.startsWith("year") || s.startsWith("ann")) return "yearly";
        return "monthly";
    }

    private String resolveUrl(String explicit, String fallbackPath) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String origin = defaultOrigin();
        return origin + (fallbackPath.startsWith("/") ? fallbackPath : "/" + fallbackPath);
    }

    private String defaultOrigin() {
        String configured = stripeConfig.getPublicOrigin();
        if (configured != null && !configured.isBlank()) {
            return configured.replaceAll("/$", "");
        }
        return "http://127.0.0.1:4200";
    }

    private void ensureStripeKey() {
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            Stripe.apiKey = stripeConfig.getStripeApiKey();
        }
        if (Stripe.apiKey == null || Stripe.apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Stripe is not configured on backend. Set STRIPE_SECRET_KEY and restart backend.");
        }
    }
}
