package com.vicinity24.core.linkedstore.api.service;

import com.vicinity24.core.linkedstore.api.config.AuthProperties;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final AuthProperties authProperties;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final String CLAIM_STORE_ID = "store_id";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_IS_GLOBAL_ADMIN = "is_global_admin";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_TOKEN_TYPE = "token_type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    public record TokenPair(String accessToken, long accessExpiresAt,
                            String refreshToken, long refreshExpiresAt) {}

    public TokenPair issueTokens(UserAccount user) {
        Instant now = Instant.now();
        long accessSeconds = authProperties.getAccessTokenMinutes() * 60L;
        long refreshSeconds = authProperties.getRefreshTokenDays() * 24L * 3600L;
        Instant accessExp = now.plusSeconds(accessSeconds);
        Instant refreshExp = now.plusSeconds(refreshSeconds);

        Map<String, Object> common = new HashMap<>();
        common.put(CLAIM_EMAIL, user.getEmail());
        common.put(CLAIM_ROLE, user.getRole() == null ? UserRole.CLERK.name() : user.getRole().name());
        common.put(CLAIM_IS_GLOBAL_ADMIN, user.isGlobalAdminEffective());
        if (user.getStoreId() != null) {
            common.put(CLAIM_STORE_ID, user.getStoreId().toString());
        }

        String access = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .issuer(authProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(accessExp))
                .claims(common)
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .signWith(signingKey())
                .compact();

        String refresh = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .issuer(authProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(refreshExp))
                .claims(common)
                .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .signWith(signingKey())
                .compact();

        return new TokenPair(access, accessExp.getEpochSecond(), refresh, refreshExp.getEpochSecond());
    }

    public Claims parse(String token, String expectedType) {
        if (token == null || token.isBlank()) throw new JwtException("Empty token");
        Claims claims = Jwts.parser()
                .verifyWith(signingKey())
                .requireIssuer(authProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String actualType = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (expectedType != null && !expectedType.equals(actualType)) {
            throw new JwtException("Unexpected token type: " + actualType);
        }
        return claims;
    }

    public Claims parseAccess(String token) { return parse(token, TYPE_ACCESS); }
    public Claims parseRefresh(String token) { return parse(token, TYPE_REFRESH); }

    public String randomRefreshHandle() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return "ls_ref_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private SecretKey signingKey() {
        String secret = authProperties.getJwtSecret();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 64) {
            byte[] padded = new byte[64];
            System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 64));
            bytes = padded;
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
