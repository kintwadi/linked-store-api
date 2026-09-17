package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.AuthTokenResponse;
import com.vicinity24.core.linkedstore.api.dto.CurrentUserResponse;
import com.vicinity24.core.linkedstore.api.dto.InvitePreviewResponse;
import com.vicinity24.core.linkedstore.api.dto.LoginRequest;
import com.vicinity24.core.linkedstore.api.dto.RefreshTokenRequest;
import com.vicinity24.core.linkedstore.api.dto.RegisterStoreRequest;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.StoreInvite;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.StoreInviteRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.service.AuthService;
import com.vicinity24.core.linkedstore.api.service.JwtService;
import com.vicinity24.core.linkedstore.api.service.PasswordService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final UserAccountRepository userAccountRepository;
    private final StoreInviteRepository storeInviteRepository;
    private final StoreRepository storeRepository;
    private final PasswordService passwordService;
    private final AuthenticationFacade authenticationFacade;

    @Value("${linkedstore.auth.api-base-origin:http://localhost:4200}")
    private String apiBaseOrigin;

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterStoreRequest request) {
        if (request.getInviteToken() != null && !request.getInviteToken().isBlank()) {
            return registerWithInvite(request);
        }

        if (Boolean.FALSE.equals(request.getIsStoreAdmin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An invite token is required to join an existing store as a representative or clerk");
        }

        if ((request.getBusinessName() == null || request.getBusinessName().isBlank())
                || request.getLatitude() == null || request.getLongitude() == null
                || request.getCountryCode() == null || request.getCountryCode().isBlank()
                || request.getCurrencyCode() == null || request.getCurrencyCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either provide a valid invite token to join an existing store, or complete all store-creation fields: businessName, latitude, longitude, countryCode, currencyCode");
        }

        UserAccount user = authService.registerStoreAndUser(request);
        JwtService.TokenPair tokenPair = jwtService.issueTokens(user);
        user.setRefreshTokenHash(hashToken(tokenPair.refreshToken()));
        user.setLastLoginAt(OffsetDateTime.now());
        userAccountRepository.save(user);
        AuthTokenResponse response = buildTokenResponse(tokenPair, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private ResponseEntity<?> registerWithInvite(RegisterStoreRequest request) {
        StoreInvite invite = storeInviteRepository.findByInviteToken(request.getInviteToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid invite token"));

        if (!StoreInvite.STATUS_PENDING.equals(invite.getStatus())) {
            if (StoreInvite.STATUS_EXPIRED.equals(invite.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite has expired");
            } else if (StoreInvite.STATUS_REDEEMED.equals(invite.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite has already been redeemed");
            } else if (StoreInvite.STATUS_REVOKED.equals(invite.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite has been revoked");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite is not valid");
        }

        if (invite.getExpiresAt().isBefore(OffsetDateTime.now())) {
            invite.setStatus(StoreInvite.STATUS_EXPIRED);
            storeInviteRepository.save(invite);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite has expired");
        }

        if (invite.getPrefillEmail() != null && !invite.getPrefillEmail().isBlank()) {
            if (!request.getEmail().equalsIgnoreCase(invite.getPrefillEmail())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This invite is reserved for a different email address");
            }
        }

        if (request.getBusinessName() != null || request.getLatitude() != null || request.getLongitude() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot both join an existing store via invite and create a new store in the same request");
        }

        if (userAccountRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        Store store = storeRepository.findById(invite.getStoreId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store not found for invite"));

        String salt = passwordService.generateSalt();
        String hash = passwordService.hash(request.getPassword(), salt);

        UserAccount user = UserAccount.builder()
                .storeId(store.getId())
                .name(request.getFullName())
                .email(request.getEmail())
                .phoneNumber(request.getPhone() != null ? request.getPhone() : "")
                .passwordSalt(salt)
                .passwordHash(hash)
                .role(invite.getTargetRole())
                .globalAdmin(false)
                .build();
        user = userAccountRepository.save(user);

        invite.setStatus(StoreInvite.STATUS_REDEEMED);
        invite.setRedeemedBy(user.getId());
        invite.setRedeemedAt(OffsetDateTime.now());
        storeInviteRepository.save(invite);

        JwtService.TokenPair tokenPair = jwtService.issueTokens(user);
        user.setRefreshTokenHash(hashToken(tokenPair.refreshToken()));
        user.setLastLoginAt(OffsetDateTime.now());
        userAccountRepository.save(user);
        AuthTokenResponse response = buildTokenResponse(tokenPair, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/invites/{token}/preview")
    @Transactional
    public ResponseEntity<InvitePreviewResponse> previewInvite(
            @PathVariable("token") String token,
            HttpServletRequest httpRequest) {
        Optional<StoreInvite> inviteOpt = storeInviteRepository.findByInviteToken(token);

        if (inviteOpt.isEmpty()) {
            return ResponseEntity.ok(InvitePreviewResponse.builder()
                    .valid(false)
                    .errorMessage("Invite not found")
                    .build());
        }

        StoreInvite invite = inviteOpt.get();

        if (StoreInvite.STATUS_REVOKED.equals(invite.getStatus())) {
            return ResponseEntity.ok(InvitePreviewResponse.builder()
                    .valid(false)
                    .errorMessage("Invite revoked")
                    .build());
        }

        if (StoreInvite.STATUS_REDEEMED.equals(invite.getStatus())) {
            return ResponseEntity.ok(InvitePreviewResponse.builder()
                    .valid(false)
                    .errorMessage("Already redeemed")
                    .build());
        }

        if (StoreInvite.STATUS_EXPIRED.equals(invite.getStatus())
                || invite.getExpiresAt().isBefore(OffsetDateTime.now())) {
            if (!StoreInvite.STATUS_EXPIRED.equals(invite.getStatus())) {
                invite.setStatus(StoreInvite.STATUS_EXPIRED);
                storeInviteRepository.save(invite);
            }
            return ResponseEntity.ok(InvitePreviewResponse.builder()
                    .valid(false)
                    .errorMessage("expired")
                    .build());
        }

        Store store = storeRepository.findById(invite.getStoreId()).orElse(null);
        InvitePreviewResponse.TargetStoreInfo storeInfo = null;
        if (store != null) {
            storeInfo = InvitePreviewResponse.TargetStoreInfo.builder()
                    .id(store.getId())
                    .businessName(store.getBusinessName())
                    .logoUrl(store.getLogoUrl())
                    .countryCode(store.getCountryCode())
                    .currencyCode(store.getCurrencyCode())
                    .build();
        }

        return ResponseEntity.ok(InvitePreviewResponse.builder()
                .valid(true)
                .expiresAt(invite.getExpiresAt())
                .targetStore(storeInfo)
                .targetRole(invite.getTargetRole() != null ? invite.getTargetRole().name() : null)
                .prefillEmail(invite.getPrefillEmail())
                .build());
    }

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        UserAccount user = authService.login(request.getEmail(), request.getPassword());
        JwtService.TokenPair tokenPair = jwtService.issueTokens(user);
        user.setRefreshTokenHash(hashToken(tokenPair.refreshToken()));
        user.setLastLoginAt(OffsetDateTime.now());
        userAccountRepository.save(user);
        AuthTokenResponse response = buildTokenResponse(tokenPair, user);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        UserAccount user = authService.refresh(request.getRefreshToken());
        JwtService.TokenPair tokenPair = jwtService.issueTokens(user);
        user.setRefreshTokenHash(hashToken(tokenPair.refreshToken()));
        userAccountRepository.save(user);
        AuthTokenResponse response = buildTokenResponse(tokenPair, user);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        CurrentUser current = authenticationFacade.current();
        if (!current.isAuthenticated() || current.getUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UserAccount user = userAccountRepository.findById(current.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("UserAccount", current.getUserId().toString()));
        CurrentUserResponse response = CurrentUserResponse.builder()
                .id(user.getId())
                .storeId(user.getStoreId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .isGlobalAdmin(user.isGlobalAdminEffective())
                .lastLoginAt(user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null)
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<?> logout() {
        CurrentUser current = authenticationFacade.current();
        if (current.isAuthenticated() && current.getUserId() != null) {
            userAccountRepository.findById(current.getUserId()).ifPresent(user -> {
                user.setRefreshTokenHash(null);
                userAccountRepository.save(user);
            });
        }
        return ResponseEntity.noContent().build();
    }

    private AuthTokenResponse buildTokenResponse(JwtService.TokenPair tokenPair, UserAccount user) {
        AuthTokenResponse.UserInfo userInfo = AuthTokenResponse.UserInfo.builder()
                .userId(user.getId())
                .storeId(user.getStoreId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .isGlobalAdmin(Boolean.TRUE.equals(user.getGlobalAdmin()))
                .build();
        return AuthTokenResponse.builder()
                .accessToken(tokenPair.accessToken())
                .accessExpiresAt(tokenPair.accessExpiresAt())
                .refreshToken(tokenPair.refreshToken())
                .refreshExpiresAt(tokenPair.refreshExpiresAt())
                .user(userInfo)
                .build();
    }

    private String hashToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
