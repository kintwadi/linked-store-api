package com.vicinity24.core.linkedstore.api.service;

import com.vicinity24.core.linkedstore.api.dto.CurrentUserResponse;
import com.vicinity24.core.linkedstore.api.dto.RegisterStoreRequest;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final StoreRepository storeRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public UserAccount registerStoreAndUser(RegisterStoreRequest request) {
        if (userAccountRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        String stripeConnectId = "acct_connected_new_" + generateRandomSuffix();

        Store store = Store.builder()
                .businessName(request.getBusinessName())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .stripeConnectId(stripeConnectId)
                .logoUrl(request.getLogoUrl())
                .heroImageUrl(request.getHeroImageUrl())
                .build();
        Store savedStore = storeRepository.save(store);

        String salt = passwordService.generateSalt();
        String hash = passwordService.hash(request.getPassword(), salt);

        UserRole role = Boolean.TRUE.equals(request.getIsStoreAdmin()) ? UserRole.STORE_ADMIN : UserRole.CLERK;

        UserAccount user = UserAccount.builder()
                .storeId(savedStore.getId())
                .name(request.getFullName())
                .email(request.getEmail())
                .phoneNumber(request.getPhone())
                .passwordSalt(salt)
                .passwordHash(hash)
                .role(role)
                .globalAdmin(false)
                .build();
        return userAccountRepository.save(user);
    }

    @Transactional
    public UserAccount login(String email, String plaintext) {
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.hasPassword()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        boolean verified = passwordService.verify(plaintext, user.getPasswordHash(), user.getPasswordSalt());
        if (!verified) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        user.setLastLoginAt(OffsetDateTime.now());
        return userAccountRepository.save(user);
    }

    public UserAccount refresh(String refreshToken) {
        Claims claims = jwtService.parseRefresh(refreshToken);
        String subject = claims.getSubject();
        UUID userId = UUID.fromString(subject);

        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        String tokenHash = sha256Base64(refreshToken);
        String stored = user.getRefreshTokenHash();
        if (stored == null || stored.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revoked");
        }
        if (!PasswordService.constantTimeEquals(stored, tokenHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        return user;
    }

    private static String sha256Base64(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public CurrentUserResponse toCurrentUserResponse(UserAccount u) {
        return CurrentUserResponse.builder()
                .id(u.getId())
                .email(u.getEmail())
                .name(u.getName())
                .role(u.getRole() != null ? u.getRole().name() : null)
                .storeId(u.getStoreId())
                .isGlobalAdmin(u.isGlobalAdminEffective())
                .status(u.getStatus() != null ? u.getStatus().name() : null)
                .lastLoginAt(u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : null)
                .createdAt(u.getCreatedAt() != null ? u.getCreatedAt().toString() : null)
                .build();
    }

    private static String generateRandomSuffix() {
        byte[] bytes = new byte[12];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase();
    }
}
