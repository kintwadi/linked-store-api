package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.CreateUserRequest;
import com.vicinity24.core.linkedstore.api.dto.UpdateUserRequest;
import com.vicinity24.core.linkedstore.api.dto.UserAdminResponse;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.entity.UserStatus;
import com.vicinity24.core.linkedstore.api.exception.ResourceNotFoundException;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.service.PasswordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAccountRepository userAccountRepository;
    private final StoreRepository storeRepository;
    private final AuthenticationFacade authenticationFacade;
    private final PasswordService passwordService;

    @GetMapping("")
    public ResponseEntity<?> listUsers() {
        CurrentUser current = authenticationFacade.current();
        List<UserAccount> users;
        if (current.isGlobalAdmin()) {
            users = userAccountRepository.findAll();
        } else {
            UUID storeId = current.getStoreId();
            if (storeId == null) {
                return ResponseEntity.ok(List.of());
            }
            users = userAccountRepository.findByStoreId(storeId);
        }
        List<UserAdminResponse> result = new ArrayList<>();
        for (UserAccount user : users) {
            result.add(buildUserAdminResponse(user));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable("id") UUID id) {
        UserAccount user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UserAccount", id.toString()));
        UUID targetStoreId = user.isGlobalAdminEffective() ? null : user.getStoreId();
        authenticationFacade.requireStoreAdminOrOwner(targetStoreId);
        return ResponseEntity.ok(buildUserAdminResponse(user));
    }

    @PostMapping("")
    @Transactional
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        CurrentUser current = authenticationFacade.current();
        UUID storeId;
        if (current.isGlobalAdmin()) {
            storeId = request.getStoreId();
        } else {
            authenticationFacade.requireStoreAdminOrOwner(current.getStoreId());
            storeId = current.getStoreId();
        }

        UserRole role = UserRole.CLERK;
        if (request.getRole() != null && !request.getRole().isBlank()) {
            try { role = UserRole.valueOf(request.getRole()); }
            catch (IllegalArgumentException ignored) { /* keep default CLERK */ }
        }

        UserAccount.UserAccountBuilder builder = UserAccount.builder()
                .storeId(storeId)
                .name(request.getName())
                .email(request.getEmail())
                .phoneNumber(request.getPhone() != null ? request.getPhone() : "")
                .role(role);

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            String salt = passwordService.generateSalt();
            String hash = passwordService.hash(request.getPassword(), salt);
            builder.passwordSalt(salt)
                    .passwordHash(hash)
                    .status(UserStatus.ACTIVE);
        } else {
            builder.status(UserStatus.INVITED);
        }

        if (current.isGlobalAdmin() && Boolean.TRUE.equals(request.getIsGlobalAdmin())) {
            builder.globalAdmin(true);
            builder.role(UserRole.GLOBAL_ADMIN);
        }

        UserAccount user = builder.build();
        user = userAccountRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(buildUserAdminResponse(user));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateUser(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        UserAccount user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UserAccount", id.toString()));
        UUID targetStoreId = user.isGlobalAdminEffective() ? null : user.getStoreId();
        authenticationFacade.requireStoreAdminOrOwner(targetStoreId);

        CurrentUser current = authenticationFacade.current();

        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getRole() != null && !request.getRole().isBlank()) {
            try { user.setRole(UserRole.valueOf(request.getRole())); }
            catch (IllegalArgumentException ignored) { /* keep existing */ }
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            try { user.setStatus(UserStatus.valueOf(request.getStatus())); }
            catch (IllegalArgumentException ignored) { /* keep existing */ }
        }
        if (request.getPhone() != null) {
            user.setPhoneNumber(request.getPhone());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            String salt = passwordService.generateSalt();
            String hash = passwordService.hash(request.getPassword(), salt);
            user.setPasswordSalt(salt);
            user.setPasswordHash(hash);
            if (user.getStatus() == UserStatus.INVITED) {
                user.setStatus(UserStatus.ACTIVE);
            }
        }
        if (request.getIsGlobalAdmin() != null) {
            if (current.isGlobalAdmin()) {
                user.setGlobalAdmin(request.getIsGlobalAdmin());
                if (Boolean.TRUE.equals(request.getIsGlobalAdmin())) {
                    user.setRole(UserRole.GLOBAL_ADMIN);
                }
            }
        }

        user = userAccountRepository.save(user);
        return ResponseEntity.ok(buildUserAdminResponse(user));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteUser(@PathVariable("id") UUID id) {
        UserAccount user = userAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UserAccount", id.toString()));
        UUID targetStoreId = user.isGlobalAdminEffective() ? null : user.getStoreId();
        authenticationFacade.requireStoreAdminOrOwner(targetStoreId);
        user.setStatus(UserStatus.DELETED);
        user.setRefreshTokenHash(null);
        userAccountRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    private UserAdminResponse buildUserAdminResponse(UserAccount user) {
        String storeName = null;
        if (user.getStoreId() != null) {
            storeName = storeName(user.getStoreId());
        }
        return UserAdminResponse.builder()
                .id(user.getId())
                .storeId(user.getStoreId())
                .storeName(storeName)
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhoneNumber())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .isGlobalAdmin(user.isGlobalAdminEffective())
                .hasPassword(user.hasPassword())
                .lastLoginAt(user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null)
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .updatedAt(user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null)
                .build();
    }

    private String storeName(UUID storeId) {
        return storeRepository.findById(storeId).map(Store::getBusinessName).orElse(null);
    }
}
