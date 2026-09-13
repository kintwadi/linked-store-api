package com.vicinity24.core.linkedstore.api.service;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.vicinity24.core.linkedstore.api.dto.VerifyPickupRequest;
import com.vicinity24.core.linkedstore.api.dto.VerifyPickupResponse;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.exception.InvalidQrTokenException;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.exception.RoleNotAuthorizedException;
import com.vicinity24.core.linkedstore.api.exception.TransactionStateException;
import com.vicinity24.core.linkedstore.api.repository.QrTokenRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreUserRepository;
import com.vicinity24.core.linkedstore.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final QrTokenRepository qrTokenRepository;
    private final StoreUserRepository storeUserRepository;
    private final TransactionRepository transactionRepository;

    private static final Set<StoreUserRole> FULFILLMENT_SCAN_ROLES =
            Set.of(StoreUserRole.OWNER, StoreUserRole.CLERK);

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VerifyPickupResponse verifyPickup(VerifyPickupRequest request) {
        String providedSecureToken = request.getSecureToken() != null ? request.getSecureToken().trim() : null;
        String providedFallbackCode = request.getFallbackCode() != null ? request.getFallbackCode().trim().replaceAll("\\s+", "") : null;
        UUID scanningUserId = request.getScanningUserId();
        OffsetDateTime now = OffsetDateTime.now();

        if ((providedSecureToken == null || providedSecureToken.isBlank())
                && (providedFallbackCode == null || providedFallbackCode.isBlank())) {
            throw new InvalidQrTokenException(
                    "Either secureToken or fallbackCode is required",
                    null,
                    InvalidQrTokenException.RejectReason.TOKEN_NOT_FOUND);
        }

        String displayToken = providedSecureToken != null ? providedSecureToken : providedFallbackCode;

        QrToken qrToken = null;
        if (providedSecureToken != null && !providedSecureToken.isBlank()) {
            qrToken = qrTokenRepository.findBySecureToken(providedSecureToken).orElse(null);
        }
        if (qrToken == null && providedFallbackCode != null && !providedFallbackCode.isBlank()) {
            qrToken = qrTokenRepository.findByFallbackCode(providedFallbackCode).orElse(null);
        }
        if (qrToken == null) {
            throw new InvalidQrTokenException(
                    "QR / fallback token not found",
                    displayToken,
                    InvalidQrTokenException.RejectReason.TOKEN_NOT_FOUND);
        }

        String secureToken = qrToken.getSecureToken();

        if (qrToken.getScannedAt() != null) {
            throw new InvalidQrTokenException(
                    "QR token has already been scanned; replay attack detected",
                    secureToken,
                    InvalidQrTokenException.RejectReason.TOKEN_ALREADY_SCANNED);
        }

        if (qrToken.getExpiresAt().isBefore(now)) {
            throw new InvalidQrTokenException(
                    "QR token has expired",
                    secureToken,
                    InvalidQrTokenException.RejectReason.TOKEN_EXPIRED);
        }

        UUID transactionId = qrToken.getTransactionId();
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId.toString()));

        if (transaction.getStatus() != TransactionStatus.PAID) {
            throw new InvalidQrTokenException(
                    "Parent transaction is not in PAID state; current state: " + transaction.getStatus(),
                    secureToken,
                    InvalidQrTokenException.RejectReason.TRANSACTION_NOT_IN_PAID_STATE);
        }

        UUID fulfillingStoreId = transaction.getFulfillingStoreId();
        StoreUser scanningUser = storeUserRepository.findById(scanningUserId)
                .orElseThrow(() -> new ResourceNotFoundException("StoreUser", scanningUserId.toString()));

        if (!scanningUser.getStoreId().equals(fulfillingStoreId)) {
            throw new InvalidQrTokenException(
                    "Scanning user does not belong to the fulfilling store",
                    secureToken,
                    InvalidQrTokenException.RejectReason.UNAUTHORIZED_SCANNER);
        }

        StoreUserRole actualRole = scanningUser.getRole();
        if (!FULFILLMENT_SCAN_ROLES.contains(actualRole)) {
            throw new RoleNotAuthorizedException(
                    "User role " + actualRole + " is not authorized to scan pickup QR codes",
                    scanningUserId, actualRole, FULFILLMENT_SCAN_ROLES);
        }

        int scannedRows = qrTokenRepository.markAsScannedIfNotAlreadyScanned(qrToken.getId(), now);
        if (scannedRows == 0) {
            QrToken refreshed = qrTokenRepository.findById(qrToken.getId()).orElse(qrToken);
            if (refreshed.getScannedAt() != null) {
                throw new InvalidQrTokenException(
                        "QR token was concurrently scanned; replay attack detected",
                        secureToken,
                        InvalidQrTokenException.RejectReason.TOKEN_ALREADY_SCANNED);
            }
            throw new InvalidQrTokenException(
                    "Failed to mark token as scanned; please retry",
                    secureToken,
                    InvalidQrTokenException.RejectReason.INVALID_SIGNATURE);
        }

        int txRows = transactionRepository.updateStatusIfCurrentStatusIs(
                transactionId,
                TransactionStatus.PICKED_UP,
                TransactionStatus.PAID);
        if (txRows == 0) {
            Transaction refreshed = transactionRepository.findById(transactionId).orElse(transaction);
            if (refreshed.getStatus() == TransactionStatus.PICKED_UP) {
                throw new InvalidQrTokenException(
                        "Pickup was already concurrently verified; replay attack detected",
                        secureToken,
                        InvalidQrTokenException.RejectReason.TOKEN_ALREADY_SCANNED);
            }
            throw new TransactionStateException(
                    "Transaction state changed during verification; expected PAID, got " + refreshed.getStatus(),
                    transactionId, refreshed.getStatus(), TransactionStatus.PAID);
        }

        OffsetDateTime settledAt = now;
        try {
            if (transaction.getStripePaymentIntentId() != null) {
                PaymentIntent pi = PaymentIntent.retrieve(transaction.getStripePaymentIntentId());
                if ("requires_capture".equals(pi.getStatus())) {
                    pi.capture();
                    log.info("Deferred Stripe capture succeeded at pickup: pi={}, tx={}",
                            pi.getId(), transactionId);
                }
            }
        } catch (StripeException e) {
            log.warn("Stripe capture at pickup time failed for tx={}. Transfers already scheduled in checkout.",
                    transactionId, e);
        }

        log.info("Pickup verified successfully: tx={}, qr={}, scannedBy={}, scannedAt={}",
                transactionId, qrToken.getId(), scanningUserId, now);

        return VerifyPickupResponse.builder()
                .transactionId(transactionId)
                .transactionStatus(TransactionStatus.PICKED_UP.name())
                .qrTokenId(qrToken.getId())
                .scannedAt(now)
                .settledAt(settledAt)
                .fulfillingStoreId(fulfillingStoreId)
                .originatingStoreId(transaction.getOriginatingStoreId())
                .wholesalePayoutCents(transaction.getWholesalePayoutCents())
                .arbitrageMarginCents(transaction.getArbitrageMarginCents())
                .fulfillmentNote("Ledger entries confirmed. Wholesale routed to Fulfilling Store; Margin routed to Originating Store.")
                .build();
    }

    public QrToken getQrTokenByTransaction(UUID transactionId) {
        return qrTokenRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("QrToken for Transaction",
                        transactionId.toString()));
    }

    public boolean isTokenValid(String secureToken) {
        return qrTokenRepository.findValidTokenBySecureToken(secureToken, OffsetDateTime.now())
                .isPresent();
    }
}
