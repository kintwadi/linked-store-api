package com.vicinity24.core.linkedstore.api.returns.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.refund.entity.Refund;
import com.vicinity24.core.linkedstore.api.returns.dto.InspectionCountsResponse;
import com.vicinity24.core.linkedstore.api.returns.dto.InspectionSummaryResponse;
import com.vicinity24.core.linkedstore.api.returns.dto.PaginatedInspectionListResponse;
import com.vicinity24.core.linkedstore.api.returns.entity.InspectionStatus;
import com.vicinity24.core.linkedstore.api.returns.entity.ReturnedInspectionRecord;
import com.vicinity24.core.linkedstore.api.returns.repository.ReturnedInspectionRecordRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class ReturnedInspectionService {

    private final ReturnedInspectionRecordRepository repo;
    private final ProductVariantRepository variantRepo;
    private final StoreRepository storeRepo;
    private final TransactionRepository txRepo;
    private final TransactionEventBroadcaster broadcaster;
    private final ObjectMapper om;
    private final AuthenticationFacade authFacade;

    @PersistenceContext
    private EntityManager em;

    public ReturnedInspectionService(
            ReturnedInspectionRecordRepository repo,
            ProductVariantRepository variantRepo,
            StoreRepository storeRepo,
            TransactionRepository txRepo,
            TransactionEventBroadcaster broadcaster,
            ObjectMapper om,
            AuthenticationFacade authFacade
    ) {
        this.repo = repo;
        this.variantRepo = variantRepo;
        this.storeRepo = storeRepo;
        this.txRepo = txRepo;
        this.broadcaster = broadcaster;
        this.om = om;
        this.authFacade = authFacade;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReturnedInspectionRecord createInspectionRecordIfNotExists(Refund completedRefund, Transaction tx) {
        try {
            Optional<ReturnedInspectionRecord> existing = repo.findByRefundId(completedRefund.getId());
            if (existing.isPresent()) {
                return existing.get();
            }

            Integer qty = 1;
            try {
                java.lang.reflect.Field f = tx.getClass().getDeclaredField("itemsCount");
                f.setAccessible(true);
                Object val = f.get(tx);
                if (val instanceof Integer ic && ic != null && ic > 0) {
                    qty = ic;
                }
            } catch (Exception ignored) {
                qty = 1;
            }

            String rawNotes = Optional.ofNullable(completedRefund.getReason())
                    .map(s -> "Refund reason: " + s)
                    .orElse("Refund created");
            String safeNotes = rawNotes.length() > 2000 ? rawNotes.substring(0, 2000) : rawNotes;

            ReturnedInspectionRecord record = ReturnedInspectionRecord.builder()
                    .refundId(completedRefund.getId())
                    .transactionId(tx.getId())
                    .variantId(tx.getVariantId())
                    .productId(tx.getProductId())
                    .fulfillingStoreId(tx.getFulfillingStoreId())
                    .originatingStoreId(tx.getOriginatingStoreId())
                    .quantity(qty)
                    .status(InspectionStatus.UNDER_INSPECTION)
                    .notes(safeNotes)
                    .build();

            ReturnedInspectionRecord saved = repo.saveAndFlush(record);

            try {
                broadcastTxEvent(saved, TxEventType.RETURN_RECEIVED, "Return received. Pending inspection.");
            } catch (Exception ex) {
                log.warn("ReturnedInspectionService: broadcast RETURN_RECEIVED failed recordId={}", saved.getId(), ex);
            }

            return saved;
        } catch (Exception ex) {
            log.error("ReturnedInspectionService: createInspectionRecordIfNotExists failed refundId={} txId={}: {}",
                    completedRefund.getId(), tx.getId(), ex.getMessage());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PaginatedInspectionListResponse listForCaller(Authentication auth, Pageable page, String statusFilter, String search) {
        CurrentUser current = authFacade.current();
        boolean isAdmin = current.isGlobalAdmin();
        UUID callerStoreId = current.getStoreId();

        Page<ReturnedInspectionRecord> pageResult;
        if (isAdmin) {
            pageResult = repo.findAllByOrderByCreatedAtDesc(page);
        } else if (callerStoreId != null) {
            pageResult = repo.findByEitherStoreId(callerStoreId, page);
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: no store scope or admin role.");
        }

        List<ReturnedInspectionRecord> records = new ArrayList<>(pageResult.getContent());

        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                InspectionStatus target = InspectionStatus.valueOf(statusFilter.toUpperCase());
                records = records.stream().filter(r -> r.getStatus() == target).toList();
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<UUID> variantIds = records.stream()
                .map(ReturnedInspectionRecord::getVariantId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, ProductVariant> variantMap = new HashMap<>();
        if (!variantIds.isEmpty()) {
            List<ProductVariant> variants = variantRepo.findAllById(variantIds);
            for (ProductVariant v : variants) {
                variantMap.put(v.getId(), v);
            }
            for (UUID vid : variantIds) {
                if (!variantMap.containsKey(vid)) {
                    variantRepo.findByIdWithProduct(vid).ifPresent(pv -> variantMap.put(vid, pv));
                }
            }
        }

        Set<UUID> storeIds = new HashSet<>();
        for (ReturnedInspectionRecord r : records) {
            if (r.getFulfillingStoreId() != null) storeIds.add(r.getFulfillingStoreId());
            if (r.getOriginatingStoreId() != null) storeIds.add(r.getOriginatingStoreId());
        }
        Map<UUID, Store> storeMap = new HashMap<>();
        if (!storeIds.isEmpty()) {
            List<Store> stores = storeRepo.findAllById(storeIds);
            for (Store s : stores) {
                storeMap.put(s.getId(), s);
            }
        }

        if (search != null && !search.isBlank()) {
            String sLower = search.toLowerCase();
            records = records.stream().filter(r -> {
                ProductVariant pv = variantMap.get(r.getVariantId());
                if (pv != null) {
                    if (pv.getSku() != null && pv.getSku().toLowerCase().contains(sLower)) return true;
                    if (pv.getProduct() != null && pv.getProduct().getTitle() != null
                            && pv.getProduct().getTitle().toLowerCase().contains(sLower)) return true;
                }
                Store sf = storeMap.get(r.getFulfillingStoreId());
                if (sf != null && sf.getBusinessName() != null && sf.getBusinessName().toLowerCase().contains(sLower)) return true;
                Store so = storeMap.get(r.getOriginatingStoreId());
                if (so != null && so.getBusinessName() != null && so.getBusinessName().toLowerCase().contains(sLower)) return true;
                return false;
            }).toList();
        }

        List<InspectionSummaryResponse> dtoList = records.stream()
                .map(r -> toDto(r, storeMap, variantMap, isAdmin, callerStoreId))
                .collect(Collectors.toList());

        InspectionCountsResponse counts;
        List<Object[]> countRows;
        if (isAdmin) {
            countRows = repo.findGlobalStatusCounts();
        } else if (callerStoreId != null) {
            countRows = repo.findStatusCountsForFulfillingStore(callerStoreId);
        } else {
            countRows = Collections.emptyList();
        }
        int under = 0, passed = 0, rejected = 0, restocked = 0, total = 0;
        for (Object[] row : countRows) {
            if (row.length >= 2 && row[0] instanceof InspectionStatus st && row[1] instanceof Number cnt) {
                int c = cnt.intValue();
                total += c;
                switch (st) {
                    case UNDER_INSPECTION -> under = c;
                    case PASSED_INSPECTION -> passed = c;
                    case REJECTED -> rejected = c;
                    case RESTOCKED -> restocked = c;
                }
            }
        }
        counts = new InspectionCountsResponse(under, passed, rejected, restocked, total);

        long totalElements = pageResult.getTotalElements();
        int totalPages = pageResult.getTotalPages();

        return new PaginatedInspectionListResponse(
                dtoList,
                pageResult.getNumber(),
                pageResult.getSize(),
                totalElements,
                totalPages,
                counts
        );
    }

    public InspectionSummaryResponse findById(UUID recordId, Authentication auth) {
        CurrentUser current = authFacade.current();
        boolean isAdmin = current.isGlobalAdmin();
        UUID callerStoreId = current.getStoreId();

        ReturnedInspectionRecord record = repo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inspection record not found: " + recordId));

        if (!isAdmin) {
            if (callerStoreId == null
                    || (!callerStoreId.equals(record.getFulfillingStoreId())
                        && !callerStoreId.equals(record.getOriginatingStoreId()))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only Global Admin or related store can view this inspection.");
            }
        }

        return buildSingleDto(record, isAdmin, callerStoreId);
    }

    public InspectionSummaryResponse approve(UUID recordId, String notesOrNull, Authentication auth) {
        CurrentUser current = authFacade.current();
        boolean isAdmin = current.isGlobalAdmin();
        UUID callerStoreId = current.getStoreId();

        ReturnedInspectionRecord record = repo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inspection record not found: " + recordId));

        if (!isAdmin) {
            if (callerStoreId == null || !callerStoreId.equals(record.getFulfillingStoreId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only Global Admin or fulfilling store can approve this inspection.");
            }
        }

        if (record.getStatus() == InspectionStatus.PASSED_INSPECTION || record.getStatus() == InspectionStatus.RESTOCKED) {
            return buildSingleDto(record, isAdmin, callerStoreId);
        }
        if (record.getStatus() == InspectionStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rejected record cannot be approved.");
        }

        ProductVariant variant = variantRepo.findById(record.getVariantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Product variant not found for inspection record: " + recordId));

        Integer currentVersion = variant.getVersion();
        if (currentVersion == null) currentVersion = 0;

        int rows = em.createQuery(
                        "UPDATE ProductVariant v SET v.stockQuantity = v.stockQuantity + :qty, " +
                                "v.version = v.version + 1 WHERE v.id = :variantId AND v.version = :version")
                .setParameter("qty", record.getQuantity())
                .setParameter("variantId", record.getVariantId())
                .setParameter("version", currentVersion)
                .executeUpdate();

        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Optimistic lock conflict — another user modified this product or already approved.");
        }

        record.setStatus(InspectionStatus.RESTOCKED);
        record.setInspectedAt(OffsetDateTime.now());
        record.setInspectedByUserId(extractUserIdFromCurrent(current));
        record.setInspectedByStoreId(callerStoreId != null ? callerStoreId.toString() : null);

        if (notesOrNull != null && !notesOrNull.isBlank()) {
            String existing = record.getNotes() != null ? record.getNotes() : "";
            String combined = existing.isBlank() ? notesOrNull : existing + "\n" + notesOrNull;
            if (combined.length() > 2000) combined = combined.substring(0, 2000);
            record.setNotes(combined);
        }

        repo.save(record);

        try {
            broadcastTxEvent(record, TxEventType.INSPECTION_PASSED, "Return passed inspection; item restocked.");
        } catch (Exception ex) {
            log.warn("ReturnedInspectionService: broadcast INSPECTION_PASSED failed recordId={}", record.getId(), ex);
        }

        return buildSingleDto(record, isAdmin, callerStoreId);
    }

    public InspectionSummaryResponse reject(UUID recordId, String rejectionReason, Authentication auth) {
        CurrentUser current = authFacade.current();
        boolean isAdmin = current.isGlobalAdmin();
        UUID callerStoreId = current.getStoreId();

        ReturnedInspectionRecord record = repo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inspection record not found: " + recordId));

        if (!isAdmin) {
            if (callerStoreId == null || !callerStoreId.equals(record.getFulfillingStoreId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only Global Admin or fulfilling store can reject this inspection.");
            }
        }

        if (record.getStatus() == InspectionStatus.REJECTED) {
            return buildSingleDto(record, isAdmin, callerStoreId);
        }
        if (record.getStatus() == InspectionStatus.PASSED_INSPECTION || record.getStatus() == InspectionStatus.RESTOCKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already approved/restocked record cannot be rejected.");
        }

        record.setStatus(InspectionStatus.REJECTED);
        record.setInspectedAt(OffsetDateTime.now());
        record.setInspectedByUserId(extractUserIdFromCurrent(current));
        record.setInspectedByStoreId(callerStoreId != null ? callerStoreId.toString() : null);

        String reason = (rejectionReason != null && !rejectionReason.isBlank())
                ? rejectionReason : "No reason provided";
        String existing = record.getNotes() != null ? record.getNotes() : "";
        String combined = existing.isBlank()
                ? "Rejection reason: " + reason
                : existing + "\nRejection reason: " + reason;
        if (combined.length() > 2000) combined = combined.substring(0, 2000);
        record.setNotes(combined);

        repo.save(record);

        try {
            broadcastTxEvent(record, TxEventType.INSPECTION_FAILED, "Return rejected; item not restocked.");
        } catch (Exception ex) {
            log.warn("ReturnedInspectionService: broadcast INSPECTION_FAILED failed recordId={}", record.getId(), ex);
        }

        return buildSingleDto(record, isAdmin, callerStoreId);
    }

    private InspectionSummaryResponse buildSingleDto(ReturnedInspectionRecord record, boolean isAdmin, UUID callerStoreId) {
        Map<UUID, Store> storeMap = new HashMap<>();
        Set<UUID> sids = new HashSet<>();
        if (record.getFulfillingStoreId() != null) sids.add(record.getFulfillingStoreId());
        if (record.getOriginatingStoreId() != null) sids.add(record.getOriginatingStoreId());
        if (!sids.isEmpty()) {
            for (Store s : storeRepo.findAllById(sids)) storeMap.put(s.getId(), s);
        }
        Map<UUID, ProductVariant> variantMap = new HashMap<>();
        if (record.getVariantId() != null) {
            variantRepo.findByIdWithProduct(record.getVariantId()).ifPresent(v -> variantMap.put(v.getId(), v));
            if (!variantMap.containsKey(record.getVariantId())) {
                variantRepo.findById(record.getVariantId()).ifPresent(v -> variantMap.put(v.getId(), v));
            }
        }
        return toDto(record, storeMap, variantMap, isAdmin, callerStoreId);
    }

    private void broadcastTxEvent(ReturnedInspectionRecord r, TxEventType type, String defaultMessage) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            String message = defaultMessage;
            if (r.getQuantity() != null && r.getQuantity() > 1) {
                message = message + " (Qty: " + r.getQuantity() + ")";
            }

            TxEvent event = TxEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .type(type)
                    .createdAt(now)
                    .transactionId(r.getTransactionId())
                    .storeId(r.getFulfillingStoreId())
                    .fulfillingStoreId(r.getFulfillingStoreId())
                    .originatingStoreId(r.getOriginatingStoreId())
                    .variantId(r.getVariantId())
                    .productId(r.getProductId())
                    .productTitle(null)
                    .productImageUrl(null)
                    .sku(null)
                    .retailPrice(null)
                    .currency("USD")
                    .expiresAt(null)
                    .countdownSeconds(null)
                    .qrFallbackCode(null)
                    .runnerId(null)
                    .status(type.name())
                    .message(message)
                    .build();

            broadcaster.broadcast(event);
            log.info("ReturnedInspectionService: broadcast {} recordId={}", type, r.getId());
        } catch (Exception ex) {
            log.warn("ReturnedInspectionService: broadcast {} failed recordId={}", type, r.getId(), ex);
        }
    }

    private InspectionSummaryResponse toDto(ReturnedInspectionRecord r,
                                             Map<UUID, Store> storeMap,
                                             Map<UUID, ProductVariant> variantMap,
                                             boolean isAdmin,
                                             UUID callerStoreId) {
        UUID fulfillId = r.getFulfillingStoreId();
        UUID originId = r.getOriginatingStoreId();
        UUID variantId = r.getVariantId();

        String storeNameFulfilling = null;
        String storeNameOriginating = null;
        if (fulfillId != null && storeMap.containsKey(fulfillId)) {
            storeNameFulfilling = storeMap.get(fulfillId).getBusinessName();
        }
        if (originId != null && storeMap.containsKey(originId)) {
            storeNameOriginating = storeMap.get(originId).getBusinessName();
        }

        String productTitle = null;
        String sku = null;
        String productImageUrl = null;
        ProductVariant pv = variantId != null ? variantMap.get(variantId) : null;
        if (pv != null) {
            sku = pv.getSku();
            productImageUrl = pv.getImageUrl();
            if (pv.getProduct() != null) {
                productTitle = pv.getProduct().getTitle();
                if (productImageUrl == null) productImageUrl = pv.getProduct().getPrimaryImageUrl();
            }
        }

        boolean canAct = (isAdmin || (callerStoreId != null && callerStoreId.equals(fulfillId)))
                && r.getStatus() == InspectionStatus.UNDER_INSPECTION;

        DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        String createdAtStr = r.getCreatedAt() != null ? r.getCreatedAt().format(fmt) : null;
        String inspectedAtStr = r.getInspectedAt() != null ? r.getInspectedAt().format(fmt) : null;

        return new InspectionSummaryResponse(
                r.getId(),
                r.getRefundId(),
                r.getTransactionId(),
                r.getVariantId(),
                r.getProductId(),
                productTitle,
                sku,
                productImageUrl,
                fulfillId,
                storeNameFulfilling,
                originId,
                storeNameOriginating,
                r.getQuantity(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getNotes(),
                createdAtStr,
                inspectedAtStr,
                r.getInspectedByUserId(),
                r.getInspectedByStoreId(),
                canAct
        );
    }

    private String extractUserIdFromCurrent(CurrentUser current) {
        if (current == null) return null;
        if (current.getUserId() != null) return current.getUserId().toString();
        if (current.getEmail() != null) return current.getEmail();
        return null;
    }
}
