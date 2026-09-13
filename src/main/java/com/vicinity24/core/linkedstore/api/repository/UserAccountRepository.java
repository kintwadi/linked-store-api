package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<UserAccount> findByStoreId(UUID storeId);

    @Query("SELECT u FROM UserAccount u WHERE u.storeId = :storeId AND u.status <> 'DELETED'")
    List<UserAccount> findActiveByStoreId(@Param("storeId") UUID storeId);

    @Query("SELECT u FROM UserAccount u WHERE u.storeId = :storeId AND u.role = :role AND u.status <> 'DELETED'")
    List<UserAccount> findActiveByStoreIdAndRole(@Param("storeId") UUID storeId, @Param("role") UserRole role);

    Optional<UserAccount> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("SELECT u FROM UserAccount u WHERE (u.globalAdmin = true OR u.role = 'GLOBAL_ADMIN') AND u.status = 'ACTIVE'")
    List<UserAccount> findGlobalAdmins();
}
