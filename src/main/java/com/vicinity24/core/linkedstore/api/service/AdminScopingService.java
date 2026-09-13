package com.vicinity24.core.linkedstore.api.service;

import com.vicinity24.core.linkedstore.api.entity.Transaction;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminScopingService {

    public Specification<Transaction> visibleTransactionIds(CurrentUser current) {
        return (root, query, cb) -> {
            if (current == null || !current.isAuthenticated()) {
                return cb.disjunction();
            }
            if (current.isGlobalAdmin()) {
                return cb.conjunction();
            }
            UUID storeId = current.getStoreId();
            if (storeId == null) {
                return cb.disjunction();
            }
            Predicate originating = cb.equal(root.get("originatingStoreId"), storeId);
            Predicate fulfilling = cb.equal(root.get("fulfillingStoreId"), storeId);
            return cb.or(originating, fulfilling);
        };
    }

    public Set<UUID> visibleStoreIds(CurrentUser current) {
        if (current == null || !current.isAuthenticated()) {
            return Collections.emptySet();
        }
        if (current.isGlobalAdmin()) {
            return null;
        }
        UUID storeId = current.getStoreId();
        if (storeId == null) {
            return Collections.emptySet();
        }
        return Collections.singleton(storeId);
    }

    public boolean canActOnUser(CurrentUser caller, UserAccount target) {
        if (caller == null || !caller.isAuthenticated() || target == null) {
            return false;
        }
        if (caller.isGlobalAdmin()) {
            return true;
        }
        UserRole callerRole = caller.getRole();
        boolean isStoreAdmin = callerRole == UserRole.STORE_ADMIN || callerRole == UserRole.OWNER;
        if (!isStoreAdmin) {
            return false;
        }
        UUID callerStoreId = caller.getStoreId();
        UUID targetStoreId = target.getStoreId();
        if (callerStoreId == null || targetStoreId == null) {
            return false;
        }
        return callerStoreId.equals(targetStoreId);
    }
}
