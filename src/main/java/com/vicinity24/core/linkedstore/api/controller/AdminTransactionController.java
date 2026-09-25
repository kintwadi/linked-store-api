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

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api/admin/transactions")
@RequiredArgsConstructor
public class AdminTransactionController {

    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final StoreUserRepository storeUserRepository;
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
            itemList.add(buildTransactionResponse(tx, current));
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

        Set<UUID> myStoreIds = adminScopingService.visibleStoreIds(current);
        if (myStoreIds == null) {
            // global admin: do not filter by stores
            myStoreIds = Collections.emptySet();
        }

        final boolean globalAdmin = current.isGlobalAdmin();

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
        final Set<UUID> finalStoreIds = myStoreIds;

        // A runner sees rows that are either (a) explicitly assigned to me OR
        // (b) rows whose fulfilling store is one of my stores AND status is an
        //     operational pickup status (READY / PAID / PICKED_UP) regardless of
        //     current runner assignment (so a runner can claim unassigned work).
        // Global admins additionally see all rows without store restriction.
        Specification<Transaction> runnerSpec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("status"), finalStatus));

            Predicate assignedToMe = cb.equal(root.get("runnerId"), finalRunnerUserId);

            final Set<UUID> stores = finalStoreIds == null ? Collections.emptySet() : finalStoreIds;
            final boolean storeScoped = !stores.isEmpty();

            Predicate unassignedOrAssignedInMyStore;
            if (globalAdmin) {
                // Admin sees any row of the given status
                unassignedOrAssignedInMyStore = cb.conjunction();
            } else if (storeScoped) {
                Predicate fulfillingInStores = root.get("fulfillingStoreId").in(stores);
                unassignedOrAssignedInMyStore = fulfillingInStores;
            } else {
                // No stores (no scope): see only rows explicitly assigned to me
                unassignedOrAssignedInMyStore = assignedToMe;
            }

            predicates.add(cb.or(assignedToMe, unassignedOrAssignedInMyStore));

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> txPage = transactionRepository.findAll(runnerSpec, pageable);

        List<TransactionResponse> itemList = new ArrayList<>();
        for (Transaction tx : txPage.getContent()) {
            itemList.add(buildTransactionResponse(tx, current));
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

    /**
     * Runner self-claim endpoint. Assigns transaction.runnerId = current user if status is
     * READY / PAID / PICKED_UP and either runnerId null, or runnerId already me. Otherwise
     * (already assigned to someone else) returns 409.
     */
    @PostMapping("/me/runner/claim/{transactionId}")
    public ResponseEntity<?> claimRunner(@PathVariable UUID transactionId) {
        CurrentUser current = authenticationFacade.current();
        if (!current.isAuthenticated() || current.getUserId() == null) {
            return ResponseEntity.status(401).build();
        }
        final UUID me = current.getUserId();
        final boolean globalAdmin = current.isGlobalAdmin();
        final Set<UUID> myStoreIds = adminScopingService.visibleStoreIds(current);

        Optional<Transaction> opt = transactionRepository.findById(transactionId);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Transaction not found.");
        Transaction tx = opt.get();

        Set<UUID> stores = myStoreIds != null ? myStoreIds : Collections.emptySet();
        boolean canSee = globalAdmin || (stores.isEmpty() ? false : stores.contains(tx.getFulfillingStoreId()));
        if (!canSee) return ResponseEntity.status(403).body("Not allowed to claim items outside your stores.");

        TransactionStatus s = tx.getStatus();
        if (s != TransactionStatus.READY && s != TransactionStatus.PAID && s != TransactionStatus.PICKED_UP) {
            return ResponseEntity.badRequest().body("Can only claim READY/PAID/PICKED_UP transactions, current status=" + s);
        }

        UUID existingRunner = tx.getRunnerId();
        if (existingRunner != null && !existingRunner.equals(me)) {
            // If the runner is from a different store entirely, reject: another runner already claimed.
            return ResponseEntity.status(409).body("Transaction already assigned to another runner (" + existingRunner + ").");
        }

        tx.setRunnerId(me);
        tx = transactionRepository.save(tx);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "runnerId", me.toString(),
                "transactionId", tx.getId().toString(),
                "status", tx.getStatus().name()
        ));
    }

    /** Admin assign: STORE_ADMIN or higher can explicitly assign a runner for a transaction. */
    @PostMapping("/{transactionId}/assign-runner")
    public ResponseEntity<?> assignRunner(
            @PathVariable UUID transactionId,
            @RequestBody(required = false) Map<String, Object> body) {
        authenticationFacade.requireAtLeastRole(UserRole.STORE_ADMIN);
        CurrentUser current = authenticationFacade.current();
        Optional<Transaction> opt = transactionRepository.findById(transactionId);
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Transaction not found.");
        Transaction tx = opt.get();

        Set<UUID> stores = adminScopingService.visibleStoreIds(current);
        if (stores != null && !stores.isEmpty() && !stores.contains(tx.getFulfillingStoreId())) {
            return ResponseEntity.status(403).body("Not allowed to manage items outside your stores.");
        }

        UUID runnerId = null;
        if (body != null) {
            Object rid = body.get("runnerId");
            if (rid instanceof String s && !s.isBlank()) {
                try { runnerId = UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
            } else if (rid instanceof UUID u) {
                runnerId = u;
            }
        }
        if (runnerId == null) {
            return ResponseEntity.badRequest().body("runnerId is required in JSON body { runnerId: <uuid> }.");
        }

        Optional<StoreUser> runner = storeUserRepository.findByIdAndRole(runnerId, StoreUserRole.RUNNER);
        if (runner.isEmpty()) {
            return ResponseEntity.badRequest().body("runnerId is not a RUNNER user.");
        }
        StoreUser ru = runner.get();
        if (stores != null && !stores.isEmpty() && ru.getStoreId() != null && !stores.contains(ru.getStoreId())) {
            return ResponseEntity.badRequest().body("Runner is not part of your store.");
        }

        tx.setRunnerId(runnerId);
        tx = transactionRepository.save(tx);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "runnerId", runnerId.toString(),
                "transactionId", tx.getId().toString(),
                "status", tx.getStatus().name()
        ));
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

    private TransactionResponse buildTransactionResponse(Transaction tx, CurrentUser current) {
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

        UUID myStoreId = null;
        if (current != null && !current.isGlobalAdmin()) {
            myStoreId = current.getStoreId();
            Set<UUID> scope = adminScopingService.visibleStoreIds(current);
            if (scope != null && scope.size() == 1) {
                myStoreId = scope.iterator().next();
            }
        }

        String perspectiveRole = "NETWORK";
        UUID perspectiveStoreId = null;
        int perspectivePriceCents = tx.getTotalRetailCents();

        if (myStoreId != null) {
            if (myStoreId.equals(tx.getOriginatingStoreId())) {
                perspectiveRole = "RETAIL_HOST";
                perspectiveStoreId = tx.getOriginatingStoreId();
                perspectivePriceCents = tx.getArbitrageMarginCents() != null
                        ? tx.getArbitrageMarginCents()
                        : 0;
            } else if (myStoreId.equals(tx.getFulfillingStoreId())) {
                perspectiveRole = "WHOLESALE_SELLER";
                perspectiveStoreId = tx.getFulfillingStoreId();
                perspectivePriceCents = tx.getWholesalePayoutCents() != null
                        ? tx.getWholesalePayoutCents()
                        : 0;
            } else {
                perspectiveRole = "NETWORK";
                perspectivePriceCents = tx.getTotalRetailCents();
            }
        }

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
                .runnerId(tx.getRunnerId())
                .perspectivePriceCents(perspectivePriceCents)
                .perspectiveRole(perspectiveRole)
                .perspectiveStoreId(perspectiveStoreId)
                .build();
    }
}
