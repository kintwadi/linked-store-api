package com.vicinity24.core.linkedstore.subscription.service;

import com.vicinity24.core.linkedstore.api.entity.SubscriptionInterval;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlan;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionPlanFeature;
import com.vicinity24.core.linkedstore.api.repository.SubscriptionPlanRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.subscription.dto.AdminPlanDto;
import com.vicinity24.core.linkedstore.subscription.dto.AdminPlanFeatureDto;
import com.vicinity24.core.linkedstore.subscription.dto.UpsertPlanFeatureRequest;
import com.vicinity24.core.linkedstore.subscription.dto.UpsertPlanRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSubscriptionPlanService {

    private static final Set<String> ALLOWED_PLAN_CODES = Set.of("PRO", "CUSTOM");
    private static final int PRO_PRICE_MIN_CENTS = 1;
    private static final int MAX_FEATURES = 50;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final SubscriptionPlanRepository planRepository;
    private final AuthenticationFacade authenticationFacade;

    @Transactional(readOnly = true)
    public List<AdminPlanDto> listEditablePlans() {
        authenticationFacade.requireCanEditSubscriptionPlans();
        List<SubscriptionPlan> plans = planRepository.findAllWithFeaturesByPlanCodeIn(List.of("PRO", "CUSTOM"));
        return plans.stream()
                .filter(p -> ALLOWED_PLAN_CODES.contains(p.getPlanCode()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public AdminPlanDto upsert(String planCode, UpsertPlanRequest request) {
        authenticationFacade.requireCanEditSubscriptionPlans();
        if (planCode == null || !ALLOWED_PLAN_CODES.contains(planCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PLAN_CODE");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_REQUEST");
        }
        validateRequest(planCode, request);

        SubscriptionPlan plan = planRepository.findByPlanCode(planCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND"));

        applyRequest(plan, request);
        rewriteFeatures(plan, request.getFeatures());

        SubscriptionPlan saved = planRepository.save(plan);
        log.info("AdminSubscriptionPlanService: upserted planCode={} displayName={}", planCode, saved.getDisplayName());
        return toDto(saved);
    }

    private void validateRequest(String planCode, UpsertPlanRequest req) {
        if (req.getDisplayName() == null || req.getDisplayName().isBlank() || req.getDisplayName().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DISPLAY_NAME_REQUIRED");
        }
        if (req.getDescription() != null && req.getDescription().length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DESCRIPTION_TOO_LONG");
        }
        if ("PRO".equals(planCode)) {
            if (req.getMonthlyPriceCents() == null || req.getMonthlyPriceCents() < PRO_PRICE_MIN_CENTS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRO_PRICE_MIN");
            }
            if (req.getAnnualPriceCents() != null && req.getAnnualPriceCents() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ANNUAL_PRICE_NEGATIVE");
            }
        }
        if (req.getAnnualDiscountPercent() != null && (req.getAnnualDiscountPercent() < 0 || req.getAnnualDiscountPercent() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ANNUAL_DISCOUNT_RANGE");
        }
        if (req.getTrialDays() != null && req.getTrialDays() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRIAL_DAYS_NEGATIVE");
        }
        if (req.getMonthlyOrderLimit() != null && req.getMonthlyOrderLimit() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ORDER_LIMIT_NEGATIVE");
        }
        if (req.getMaxConnectedStores() != null && req.getMaxConnectedStores() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MAX_CONNECTED_STORES_NEGATIVE");
        }
        if (Boolean.TRUE.equals(req.getContactSalesEnabled())) {
            String email = req.getContactSalesEmail();
            String url = req.getContactSalesUrl();
            if (email == null || email.isBlank() || url == null || url.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONTACT_SALES_CONTACT_REQUIRED");
            }
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONTACT_SALES_EMAIL_INVALID");
            }
            boolean validUrl = url.startsWith("/") || url.startsWith("https://") || url.startsWith("mailto:");
            if (!validUrl) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONTACT_SALES_URL_INVALID");
            }
        }
        if (req.getBadgesCsv() != null && req.getBadgesCsv().length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BADGES_TOO_LONG");
        }
        List<UpsertPlanFeatureRequest> features = req.getFeatures() == null ? List.of() : req.getFeatures();
        if (features.size() > MAX_FEATURES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOO_MANY_FEATURES");
        }
        for (UpsertPlanFeatureRequest f : features) {
            if (f == null || f.getLabel() == null || f.getLabel().isBlank() || f.getLabel().length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FEATURE_LABEL_INVALID");
            }
        }
    }

    private void applyRequest(SubscriptionPlan plan, UpsertPlanRequest req) {
        plan.setDisplayName(req.getDisplayName().trim());
        plan.setDescription(req.getDescription() == null ? null : req.getDescription().trim());
        plan.setPriceCents(req.getMonthlyPriceCents());
        plan.setAnnualPriceCents(req.getAnnualPriceCents());
        plan.setAnnualDiscountPercent(req.getAnnualDiscountPercent());
        plan.setBillingLabelMonthly(req.getBillingLabelMonthly() == null ? null : req.getBillingLabelMonthly().trim());
        plan.setBillingLabelAnnual(req.getBillingLabelAnnual() == null ? null : req.getBillingLabelAnnual().trim());
        plan.setCurrency(req.getCurrency() == null || req.getCurrency().isBlank() ? "usd" : req.getCurrency().trim());
        plan.setTrialDays(req.getTrialDays() == null ? 0 : req.getTrialDays());
        plan.setMonthlyOrderLimit(normalizeUnlimitedQuota(req.getMonthlyOrderLimit()));
        plan.setMaxConnectedStores(normalizeUnlimitedQuota(req.getMaxConnectedStores()));
        plan.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        plan.setBadges(parseBadgesCsv(req.getBadgesCsv()));
        boolean csEnabled = Boolean.TRUE.equals(req.getContactSalesEnabled());
        plan.setContactSalesEnabled(csEnabled);
        plan.setContactSalesEmail(csEnabled && req.getContactSalesEmail() == null ? null : req.getContactSalesEmail());
        plan.setContactSalesUrl(csEnabled && req.getContactSalesUrl() == null ? null : req.getContactSalesUrl());
        plan.setIsActive(req.getIsActive() == null ? Boolean.TRUE : req.getIsActive());
        if ("PRO".equals(plan.getPlanCode())) {
            if (plan.getIntervalUnit() == null) plan.setIntervalUnit(SubscriptionInterval.MONTH);
            if (plan.getIntervalCount() == null) plan.setIntervalCount(1);
        } else if ("CUSTOM".equals(plan.getPlanCode())) {
            plan.setIntervalUnit(null);
            plan.setIntervalCount(null);
        }
    }

    private String[] parseBadgesCsv(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        String[] parts = csv.split(",");
        List<String> cleaned = new ArrayList<>();
        StringBuilder sb = null;
        for (String raw : parts) {
            if (sb == null) {
            if (raw.endsWith("\\") && !raw.endsWith("\\\\")) {
                sb = new StringBuilder(raw.substring(0, raw.length() - 1));
                sb.append(',');
            } else {
                String t = raw.replace("\\\\", "\\");
                if (!t.isBlank()) cleaned.add(t.trim());
            }
            } else {
                String s = raw;
                if (s.endsWith("\\") && !s.endsWith("\\\\")) {
                    sb.append(s, 0, s.length() - 1);
                    sb.append(',');
                } else {
                    sb.append(s);
                    String t = sb.toString().replace("\\\\", "\\");
                    sb = null;
                    if (!t.isBlank()) cleaned.add(t.trim());
                }
            }
        }
        if (sb != null) {
            String t = sb.toString().replace("\\\\", "\\");
            if (!t.isBlank()) cleaned.add(t.trim());
        }
        return cleaned.toArray(new String[0]);
    }

    private void rewriteFeatures(SubscriptionPlan plan, List<UpsertPlanFeatureRequest> requests) {
        List<UpsertPlanFeatureRequest> src = requests == null ? List.of() : new ArrayList<>(requests);
        src.sort(Comparator.comparingInt(f -> f.getDisplayOrder() == null ? Integer.MAX_VALUE : f.getDisplayOrder()));
        plan.getFeatures().clear();
        for (int i = 0; i < src.size(); i++) {
            UpsertPlanFeatureRequest r = src.get(i);
            SubscriptionPlanFeature f = SubscriptionPlanFeature.builder()
                    .plan(plan)
                    .label(r.getLabel().trim())
                    .included(r.getIncluded() == null ? Boolean.TRUE : r.getIncluded())
                    .highlight(r.getHighlight() == null ? Boolean.FALSE : r.getHighlight())
                    .displayOrder(i)
                    .build();
            plan.getFeatures().add(f);
        }
    }

    private AdminPlanDto toDto(SubscriptionPlan p) {
        List<AdminPlanFeatureDto> features = p.getFeatures() == null ? List.of() : p.getFeatures().stream()
                .sorted(Comparator.comparingInt(f -> f.getDisplayOrder() == null ? 0 : f.getDisplayOrder()))
                .map(f -> AdminPlanFeatureDto.builder()
                        .id(f.getId())
                        .label(f.getLabel())
                        .included(f.getIncluded())
                        .highlight(f.getHighlight())
                        .displayOrder(f.getDisplayOrder())
                        .build())
                .collect(Collectors.toList());
        Integer monthlyOrderLimit = normalizeUnlimitedQuota(p.getMonthlyOrderLimit());
        Integer maxConnectedStores = normalizeUnlimitedQuota(p.getMaxConnectedStores());
        return AdminPlanDto.builder()
                .id(p.getId())
                .planCode(p.getPlanCode())
                .displayName(p.getDisplayName())
                .description(p.getDescription())
                .monthlyPriceCents(p.getPriceCents())
                .annualPriceCents(p.getAnnualPriceCents())
                .annualDiscountPercent(p.getAnnualDiscountPercent())
                .billingLabelMonthly(p.getBillingLabelMonthly())
                .billingLabelAnnual(p.getBillingLabelAnnual())
                .currency(p.getCurrency())
                .trialDays(p.getTrialDays())
                .monthlyOrderLimit(monthlyOrderLimit)
                .maxConnectedStores(maxConnectedStores)
                .sortOrder(p.getSortOrder())
                .badges(p.getBadges() == null ? new String[0] : Arrays.copyOf(p.getBadges(), p.getBadges().length))
                .contactSalesEnabled(p.getContactSalesEnabled())
                .contactSalesEmail(p.getContactSalesEmail())
                .contactSalesUrl(p.getContactSalesUrl())
                .stripePriceIdLegacy(p.getStripePriceId())
                .isActive(p.getIsActive())
                .features(features)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private static Integer normalizeUnlimitedQuota(Integer v) {
        if (v == null) return null;
        if (v < 0 || v.equals(Integer.MAX_VALUE)) return null;
        return v;
    }
}
