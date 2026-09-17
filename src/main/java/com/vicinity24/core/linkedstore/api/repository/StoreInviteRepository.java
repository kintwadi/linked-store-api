package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.StoreInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StoreInviteRepository extends JpaRepository<StoreInvite, UUID> {

    Optional<StoreInvite> findByInviteToken(String inviteToken);

    @Modifying
    @Query("UPDATE StoreInvite s SET s.status = 'EXPIRED' WHERE s.status = 'PENDING' AND s.expiresAt < CURRENT_TIMESTAMP")
    int markExpiredPendingInvites();
}
