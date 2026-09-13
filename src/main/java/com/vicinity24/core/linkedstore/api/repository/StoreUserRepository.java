package com.vicinity24.core.linkedstore.api.repository;

import com.vicinity24.core.linkedstore.api.entity.StoreUser;
import com.vicinity24.core.linkedstore.api.entity.StoreUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoreUserRepository extends JpaRepository<StoreUser, UUID> {

    List<StoreUser> findByStoreId(UUID storeId);

    List<StoreUser> findByStoreIdAndRole(UUID storeId, StoreUserRole role);

    Optional<StoreUser> findByIdAndStoreId(UUID id, UUID storeId);

    Optional<StoreUser> findByIdAndRole(UUID id, StoreUserRole role);
}
