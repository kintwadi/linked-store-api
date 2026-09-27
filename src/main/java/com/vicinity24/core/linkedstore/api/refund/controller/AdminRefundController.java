package com.vicinity24.core.linkedstore.api.refund.controller;

import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.refund.dto.InitiateRefundRequest;
import com.vicinity24.core.linkedstore.api.refund.dto.RefundResponse;
import com.vicinity24.core.linkedstore.api.refund.dto.RefundedTxIdSetResponse;
import com.vicinity24.core.linkedstore.api.refund.entity.Refund;
import com.vicinity24.core.linkedstore.api.refund.entity.RefundStatus;
import com.vicinity24.core.linkedstore.api.refund.repository.RefundRepository;
import com.vicinity24.core.linkedstore.api.refund.service.RefundService;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;
    private final RefundRepository refundRepository;
    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/transactions/{txId}/refunds")
    public ResponseEntity<RefundResponse> initiateRefund(
            @PathVariable String txId,
            @RequestBody(required = false) InitiateRefundRequest bodyNullable,
            Authentication auth) {

        UUID txUuid;
        try {
            txUuid = UUID.fromString(txId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid transaction ID format: " + txId);
        }

        Integer amt = bodyNullable != null ? bodyNullable.amountCents() : null;
        String reason = bodyNullable != null ? bodyNullable.reason() : null;

        if (auth == null) {
            auth = SecurityContextHolder.getContext().getAuthentication();
        }

        CurrentUser current = authenticationFacade.current();
        Transaction tx = transactionRepository.findById(txUuid)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + txId));

        String perspective = computePerspectiveOrForbid(current, tx);

        Refund refund = refundService.initiateRefund(txUuid, amt, reason, auth);

        RefundResponse response = RefundResponse.from(refund, perspective);

        RefundStatus status = refund.getStatus();
        if (status == RefundStatus.COMPLETED) {
            return ResponseEntity.ok(response);
        } else if (status == RefundStatus.PENDING || status == RefundStatus.PROCESSING) {
            return ResponseEntity.accepted().body(response);
        } else if (status == RefundStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions/{txId}/refunds")
    public ResponseEntity<List<RefundResponse>> listRefundsForTransaction(@PathVariable String txId) {
        UUID txUuid;
        try {
            txUuid = UUID.fromString(txId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid transaction ID format: " + txId);
        }

        CurrentUser current = authenticationFacade.current();
        Transaction tx = transactionRepository.findById(txUuid)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + txId));

        String perspective = computePerspectiveOrForbid(current, tx);

        List<RefundResponse> list = refundService.listForTransaction(txUuid, perspective);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<RefundResponse> getRefundById(@PathVariable String refundId) {
        UUID refundUuid;
        try {
            refundUuid = UUID.fromString(refundId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid refund ID format: " + refundId);
        }

        CurrentUser current = authenticationFacade.current();

        Refund refund = refundRepository.findById(refundUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));

        Transaction tx = transactionRepository.findById(refund.getTransactionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found for refund: " + refundId));

        String perspective = computePerspectiveOrForbid(current, tx);

        RefundResponse response = RefundResponse.from(refund, perspective);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stores/me/refunds")
    public ResponseEntity<List<RefundResponse>> getMyStoreRefunds() {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        UUID myStoreId = store.getId();

        List<Transaction> txs = transactionRepository.findAllInvolvingStore(myStoreId);
        List<UUID> txIds = txs.stream().map(Transaction::getId).toList();

        List<RefundResponse> result = new ArrayList<>();
        for (UUID txId : txIds) {
            List<Refund> refunds = refundRepository.findAllByTransactionIdOrderByCreatedAtDesc(txId);
            for (Refund refund : refunds) {
                Transaction tx = transactionRepository.findById(txId).orElse(null);
                String perspective = computePerspectiveForStore(myStoreId, tx);
                result.add(RefundResponse.from(refund, perspective));
            }
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/stores/me/refunded-tx-ids")
    public ResponseEntity<RefundedTxIdSetResponse> getMyStoreRefundedTxIds() {
        CurrentUser current = authenticationFacade.current();
        Store store = resolveMyStore(current);
        UUID myStoreId = store.getId();

        Set<UUID> txUuids = refundService.findRefundedTxIdsForStore(myStoreId);
        Set<String> txIds = txUuids.stream().map(UUID::toString).collect(Collectors.toSet());

        return ResponseEntity.ok(new RefundedTxIdSetResponse(txIds));
    }

    private String computePerspectiveOrForbid(CurrentUser current, Transaction tx) {
        if (current.isGlobalAdmin()) {
            return "ADMIN";
        }

        UUID myStoreId = resolveCallerStoreId(current);
        if (myStoreId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Access denied: no store scope associated with user.");
        }

        if (myStoreId.equals(tx.getOriginatingStoreId())) {
            return "ORIGINATOR_HOST";
        } else if (myStoreId.equals(tx.getFulfillingStoreId())) {
            return "FULFILLER_SELLER";
        }

        throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN, "Access denied: you do not have permission to view this transaction's refunds.");
    }

    private String computePerspectiveForStore(UUID myStoreId, Transaction tx) {
        if (tx == null) {
            return "ADMIN";
        }
        if (myStoreId.equals(tx.getOriginatingStoreId())) {
            return "ORIGINATOR_HOST";
        } else if (myStoreId.equals(tx.getFulfillingStoreId())) {
            return "FULFILLER_SELLER";
        }
        return "ADMIN";
    }

    private UUID resolveCallerStoreId(CurrentUser current) {
        if (current == null || !current.isAuthenticated()) {
            return null;
        }
        UUID storeId = current.getStoreId();
        return storeId;
    }

    private Store resolveMyStore(CurrentUser current) {
        if (current.isGlobalAdmin() && current.getStoreId() == null) {
            return storeRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Store", "<none>"));
        }
        UUID storeId = current.getStoreId();
        if (storeId == null) {
            throw new ResourceNotFoundException("Store", "<not bound>");
        }
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId.toString()));
    }
}
