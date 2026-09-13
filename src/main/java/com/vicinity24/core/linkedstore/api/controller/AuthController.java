package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.AuthTokenResponse;
import com.vicinity24.core.linkedstore.api.dto.CurrentUserResponse;
import com.vicinity24.core.linkedstore.api.dto.LoginRequest;
import com.vicinity24.core.linkedstore.api.dto.RefreshTokenRequest;
import com.vicinity24.core.linkedstore.api.dto.RegisterStoreRequest;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.service.JwtService;
import com.vicinity24.core.linkedstore.api.service.AuthService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final UserAccountRepository userAccountRepository;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterStoreRequest request) {
        UserAccount user = authService.registerStoreAndUser(request);
        JwtService.TokenPair tokenPair = jwtService.issueTokens(user);
        user.setRefreshTokenHash(hashToken(tokenPair.refreshToken()));
        user.setLastLoginAt(OffsetDateTime.now());
        userAccountRepository.save(user);
        AuthTokenResponse response = buildTokenResponse(tokenPair, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
