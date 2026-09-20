package com.vicinity24.core.linkedstore.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicinity24.core.linkedstore.api.dto.AdminTransactionListResponse;
import com.vicinity24.core.linkedstore.api.dto.TransactionResponse;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.repository.*;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.service.AdminScopingService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/transactions")
@RequiredArgsConstructor
public class AdminTransactionController {

    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final ProductVariantRepository productVariantRepository;
    private final QrTokenRepository qrTokenRepository;
    private final ObjectMapper objectMapper;
    private final AuthenticationFacade authenticationFacade;
    private final AdminScopingService adminScopingService;

    @GetMapping("")
    public ResponseEntity<?> listTransactions(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "pageSize", defaultValue = "25") int pageSize,
            @RequestParam(name = "storeId", required = false) UUID storeId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "runnerId", required = false) UUID runnerId) {

        if (page < 0) page = 0;
        if (pageSize <= 0) pageSize = 25;
        if (pageSize > 200) pageSize = 200;

        CurrentUser current = authenticationFacade.current();
        authenticationFacade.requireAtLeastRole(UserRole.STORE_ADMIN);

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Transaction> spec = buildFilterSpec(current, storeId, status, runnerId);

        Page<Transaction> txPage = transactionRepository.findAll(spec, pageable);

        List<TransactionResponse> itemList = new ArrayList<>();
        for (Transaction tx : txPage.getContent()) {
            itemList.add(buildTransactionResponse(tx));
        }

        AdminTransactionListResponse response = AdminTransactionListResponse.builder()
                .items(itemList)
                .page(txPage.getNumber())
                .pageSize(txPage.getSize())
                .totalCount(txPage.getTotalElements())
                .totalElements(txPage.getTotalElements())
                .totalPages(txPage.getTotalPages())
                .hasNext(txPage.hasNext())
                .hasPrevious(txPage.hasPrevious())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me/runner")
    public ResponseEntity<?> listRunnerPickups(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "pageSize", defaultValue = "25") int pageSize,
            @RequestParam(name = "status", required = false, defaultValue = "PAID") String status) {

        if (page < 0) page = 0;
        if (pageSize <= 0) pageSize = 25;
        if (pageSize > 200) pageSize = 200;

        CurrentUser current = authenticationFacade.current();
        if (!current.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        UUID runnerUserId = current.getUserId();
        if (runnerUserId == null) {
            return ResponseEntity.status(400).body("Runner user id missing in token.");
        }

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        TransactionStatus requestedStatus;
        try {
            requestedStatus = status != null && !status.isBlank()
                    ? TransactionStatus.valueOf(status)
                    : TransactionStatus.PAID;
        } catch (IllegalArgumentException iae) {
            requestedStatus = TransactionStatus.PAID;
        }
        final TransactionStatus finalStatus = requestedStatus;
        final UUID finalRunnerUserId = runnerUserId;

        Specification<Transaction> runnerSpec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("runnerId"), finalRunnerUserId));
            predicates.add(cb.equal(root.get("status"), finalStatus));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Transaction> txPage = transactionRepository.findAll(runnerSpec, pageable);

        List<TransactionResponse> itemList = new ArrayList<>();
        for (Transaction tx : txPage.getContent()) {
            itemList.add(buildTransactionResponse(tx));
        }

        AdminTransactionListResponse response = AdminTransactionListResponse.builder()
                .items(itemList)
                .page(txPage.getNumber())
                .pageSize(txPage.getSize())
                .totalCount(txPage.getTotalElements())
                .totalElements(txPage.getTotalElements())
                .totalPages(txPage.getTotalPages())
                .hasNext(txPage.hasNext())
                .hasPrevious(txPage.hasPrevious())
                .build();

        return ResponseEntity.ok(response);
    }

    private Specification<Transaction> buildFilterSpec(
            CurrentUser current,
            UUID storeIdFilter,
            String statusFilter,
            UUID runnerIdFilter) {

        Specification<Transaction> scopeSpec = adminScopingService.visibleTransactionIds(current);

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(scopeSpec.toPredicate(root, query, cb));

            if (storeIdFilter != null) {
                Predicate originating = cb.equal(root.get("originatingStoreId"), storeIdFilter);
                Predicate fulfilling = cb.equal(root.get("fulfillingStoreId"), storeIdFilter);
                predicates.add(cb.or(originating, fulfilling));
            }

            if (statusFilter != null && !statusFilter.isBlank()) {
                predicates.add(cb.equal(root.get("status"), TransactionStatus.valueOf(statusFilter)));
            }

            if (runnerIdFilter != null) {
                predicates.add(cb.equal(root.get("runnerId"), runnerIdFilter));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private TransactionResponse buildTransactionResponse(Transaction tx) {
        UUID txId = tx.getId();

        String originatingStoreName = storeRepository.findById(tx.getOriginatingStoreId())
                .map(Store::getBusinessName)
                .orElse(null);

        String fulfillingStoreName = storeRepository.findById(tx.getFulfillingStoreId())
                .map(Store::getBusinessName)
                .orElse(null);

        UUID variantId = inventoryLockRepository.findByTransactionId(txId).stream()
                .findFirst()
                .map(InventoryLock::getVariantId)
                .orElse(null);

        if (variantId == null) {
            variantId = transactionItemRepository.findByTransactionId(txId).stream()
                    .findFirst()
                    .map(TransactionItem::getVariantId)
                    .orElse(null);
        }

        UUID productId = null;
        String productTitle = null;
        String productImageUrl = null;
        String sku = null;
        String variantAttributesJson = null;

        if (variantId != null) {
            Optional<ProductVariant> variantOpt = productVariantRepository.findByIdWithProduct(variantId);
            if (variantOpt.isPresent()) {
                ProductVariant variant = variantOpt.get();
                productId = variant.getProductId();
                sku = variant.getSku();

                if (variant.getProduct() != null) {
                    productTitle = variant.getProduct().getTitle();
                    productImageUrl = variant.getProduct().getPrimaryImageUrl() != null
                            ? variant.getProduct().getPrimaryImageUrl()
                            : variant.getImageUrl();
                } else {
                    productImageUrl = variant.getImageUrl();
                }

                if (variant.getVariantAttributes() != null) {
                    try {
                        variantAttributesJson = objectMapper.writeValueAsString(variant.getVariantAttributes());
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize variantAttributes for variantId={}", variantId, e);
                        variantAttributesJson = null;
                    }
                }
            }
        }

        QrToken qrToken = qrTokenRepository.findByTransactionId(txId).orElse(null);
        String qrSecureToken = qrToken != null ? qrToken.getSecureToken() : null;
        String qrFallbackCode = qrToken != null ? qrToken.getFallbackCode() : null;

        return TransactionResponse.builder()
                .id(tx.getId())
                .status(tx.getStatus().name())
                .originatingStoreId(tx.getOriginatingStoreId())
                .originatingStoreName(originatingStoreName)
                .fulfillingStoreId(tx.getFulfillingStoreId())
                .fulfillingStoreName(fulfillingStoreName)
                .stripePaymentIntentId(tx.getStripePaymentIntentId())
                .totalRetailCents(tx.getTotalRetailCents())
                .wholesalePayoutCents(tx.getWholesalePayoutCents())
                .arbitrageMarginCents(tx.getArbitrageMarginCents())
                .currency("USD")
                .productId(productId)
                .productTitle(productTitle)
                .productImageUrl(productImageUrl)
                .variantId(variantId)
                .sku(sku)
                .variantAttributesJson(variantAttributesJson)
                .qrSecureToken(qrSecureToken)
                .qrFallbackCode(qrFallbackCode)
                .createdAt(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null)
                .updatedAt(tx.getUpdatedAt() != null ? tx.getUpdatedAt().toString() : null)
                .build();
    }
}
