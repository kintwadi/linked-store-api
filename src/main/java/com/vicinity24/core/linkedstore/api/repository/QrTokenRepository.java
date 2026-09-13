package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.QrToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QrTokenRepository extends JpaRepository<QrToken, UUID> {

    Optional<QrToken> findBySecureToken(String secureToken);

    Optional<QrToken> findByFallbackCode(String fallbackCode);

    Optional<QrToken> findByTransactionId(UUID transactionId);

    @Query("""
        SELECT qt FROM QrToken qt
        WHERE qt.secureToken = :secureToken
          AND qt.expiresAt > :now
          AND qt.scannedAt IS NULL
    """)
    Optional<QrToken> findValidTokenBySecureToken(
            @Param("secureToken") String secureToken,
            @Param("now") OffsetDateTime now
    );

    @Query("""
        SELECT qt FROM QrToken qt
        WHERE qt.fallbackCode = :fallbackCode
          AND qt.expiresAt > :now
          AND qt.scannedAt IS NULL
    """)
    Optional<QrToken> findValidTokenByFallbackCode(
            @Param("fallbackCode") String fallbackCode,
            @Param("now") OffsetDateTime now
    );

    boolean existsByFallbackCode(String fallbackCode);

    @Modifying
    @Query("""
        UPDATE QrToken qt
        SET qt.scannedAt = :scannedAt
        WHERE qt.id = :tokenId
          AND qt.scannedAt IS NULL
    """)
    int markAsScannedIfNotAlreadyScanned(
            @Param("tokenId") UUID tokenId,
            @Param("scannedAt") OffsetDateTime scannedAt
    );
}
