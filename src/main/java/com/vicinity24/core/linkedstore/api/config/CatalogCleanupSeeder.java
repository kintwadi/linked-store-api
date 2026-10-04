package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.repository.ProductRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "seed", name = "catalog", havingValue = "false")
public class CatalogCleanupSeeder {

    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    @PersistenceContext
    private EntityManager em;

    private static final List<String> SEEDED_STORE_CODES = List.of(
            "store-downtown-01",
            "store-uptown-02",
            "store-brooklyn-03",
            "store-a-04",
            "store-b-05"
    );

    private static final List<String> SEEDED_STRIPE_CONNECT_PREFIXES = List.of(
            "acct_1UFGaNGVibqsNijL",
            "acct_1UFHDaGbVVxOVEbj",
            "acct_1UFGaNGjyrXTQ6F6",
            "acct_1UH2agGZ3vjZMcEq",
            "acct_1UH2jfGoUT7LodNw"
    );

    private static final List<String> SEEDED_PRODUCT_SKU_STEMS = List.of(
            "prd-airmax-pulse",
            "prd-af1-low",
            "prd-ultraboost-light",
            "prd-techfleece-windrunner",
            "prd-newera-940-yankees",
            "prd-nike-everyday-plus-3p"
    );

    @EventListener(ContextRefreshedEvent.class)
    @Transactional
    public void cleanupSeededCatalog() {
        List<UUID> targetedStoreIds = collectSeededStoreIds();
        if (targetedStoreIds == null) {
            log.warn("CatalogCleanupSeeder: could not enumerate stores; aborting cleanup.");
            return;
        }
        List<UUID> targetedProductIds = collectSeededProductIds();

        int deletedVariants = deleteVariantRowsByStoreOrProduct(targetedStoreIds, targetedProductIds);
        int deletedProducts = deleteProductRowsByIds(targetedProductIds);
        int deletedStoreUsers = deleteStoreUsersByStoreIds(targetedStoreIds);
        int deletedSubscriptions = deleteSubscriptionRowsByStoreIds(targetedStoreIds);
        int deletedInventoryLocks = deleteInventoryLockRowsByStoreIds(targetedStoreIds);
        int deletedInvites = deleteInviteRowsByStoreIds(targetedStoreIds);
        int deletedTxOrig = deleteTransactionRowsByStoreIds(targetedStoreIds, "originating_store_id");
        int deletedTxFulfill = deleteTransactionRowsByStoreIds(targetedStoreIds, "fulfilling_store_id");
        int deletedStores = deleteStoreRowsByIds(targetedStoreIds);
        int connectedCleanup = deleteConnectedStoresByPrefix("acct_connected_");

        List<String> names = new ArrayList<>();
        for (UUID id : targetedStoreIds) {
            storeRepository.findById(id).ifPresent(s -> names.add(s.getBusinessName()));
        }
        log.info("CatalogCleanupSeeder: seed.catalog=false → dropped seeded catalog data" +
                        " [stores={}({}) users={} subs={} locks={} invites={} txOrig={} txFl={} variants={} products={} acct_connected_={}]",
                deletedStores, names.isEmpty() ? targetedStoreIds.size() : String.join("|", names),
                deletedStoreUsers, deletedSubscriptions, deletedInventoryLocks, deletedInvites,
                deletedTxOrig, deletedTxFulfill, deletedVariants, deletedProducts, connectedCleanup);
    }

    private List<UUID> collectSeededProductIds() {
        List<UUID> ids = new ArrayList<>();
        String[] titles = new String[]{
                "Nike Air Max Pulse — Running",
                "Nike Air Force 1 Low — White on White",
                "Adidas Ultraboost Light",
                "Nike Tech Fleece Windrunner",
                "New Era 9FORTY — MLB Yankees",
                "Nike Everyday Plus Crew — 3 Pack"
        };
        try {
            Session session = em.unwrap(Session.class);
            session.doWork(conn -> {
                String placeholders = String.join(",", java.util.Arrays.stream(titles).map(x -> "?").toList());
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM products WHERE title IN (" + placeholders + ")")) {
                    for (int i = 0; i < titles.length; i++) ps.setString(i + 1, titles[i]);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) ids.add(UUID.fromString(rs.getString(1)));
                    }
                }
            });
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: cannot collect seeded product ids ({})", e.getMessage());
        }
        return ids;
    }

    private List<UUID> collectSeededStoreIds() {
        List<Store> all = storeRepository.findAll();
        List<UUID> ids = new ArrayList<>();
        for (Store s : all) {
            if (matchesSeededStore(s)) {
                ids.add(s.getId());
            }
        }
        return ids;
    }

    private boolean matchesSeededStore(Store s) {
        String code = s.getGatewayCode();
        String stripe = s.getStripeConnectId();
        String name = s.getBusinessName();
        if (code != null && SEEDED_STORE_CODES.contains(code.trim())) return true;
        if (stripe != null && SEEDED_STRIPE_CONNECT_PREFIXES.stream().anyMatch(stripe::startsWith)) return true;
        if (name == null || name.isBlank()) return false;
        String prefix = name.split("\\s+—\\s+|\\s+-\\s+|\\s*\\(")[0].trim();
        if (prefix.isBlank()) return false;
        return SEEDED_STRIPE_CONNECT_PREFIXES.stream()
                .anyMatch(p -> {
                    try {
                        String trimmed = p.startsWith("acct_1") ? p.substring("acct_1".length()).toLowerCase() : p.toLowerCase();
                        return prefix.toLowerCase().contains(trimmed.substring(0, Math.min(trimmed.length(), 6)));
                    } catch (Exception ignore) { return false; }
                })
                || List.of("Kicks & Co. — Downtown Flagship",
                        "Sole District — Uptown",
                        "Brooklyn Runs — Williamsburg",
                        "StoreA — Midtown Sneaker Lab",
                        "StoreB — Queens Outlet Hub").contains(name);
    }

    private int deleteVariantRowsByStoreOrProduct(List<UUID> storeIds, List<UUID> productIds) {
        if ((storeIds == null || storeIds.isEmpty()) && (productIds == null || productIds.isEmpty())) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                int total = 0;
                if (storeIds != null && !storeIds.isEmpty()) {
                    String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                    total += execute(conn, "DELETE FROM product_variants WHERE store_id IN (" + placeholders + ")",
                            storeIds.stream().map(x -> (Object) x).toList());
                }
                if (productIds != null && !productIds.isEmpty()) {
                    String placeholders = String.join(",", productIds.stream().map(x -> "?").toList());
                    total += execute(conn, "DELETE FROM product_variants WHERE product_id IN (" + placeholders + ")",
                            productIds.stream().map(x -> (Object) x).toList());
                }
                out[0] = total;
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: variant delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteProductRowsByIds(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", productIds.stream().map(x -> "?").toList());
                out[0] = execute(conn, "DELETE FROM products WHERE id IN (" + placeholders + ")",
                        productIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: product delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteStoreRowsByIds(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                out[0] = execute(conn, "DELETE FROM stores WHERE id IN (" + placeholders + ")",
                        storeIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: stores delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteStoreUsersByStoreIds(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                out[0] = execute(conn,
                        "DELETE FROM store_users WHERE store_id IN (" + placeholders + ") AND is_global_admin IS DISTINCT FROM TRUE",
                        storeIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: store_users delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteSubscriptionRowsByStoreIds(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                out[0] = execute(conn, "DELETE FROM subscriptions WHERE store_id IN (" + placeholders + ")",
                        storeIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: subscriptions delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteInventoryLockRowsByStoreIds(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                out[0] = execute(conn, "DELETE FROM inventory_locks WHERE store_id IN (" + placeholders + ")",
                        storeIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: inventory_locks delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteInviteRowsByStoreIds(List<UUID> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                out[0] = execute(conn, "DELETE FROM store_invites WHERE store_id IN (" + placeholders + ")",
                        storeIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: store_invites delete skipped ({})", e.getMessage());
            return 0;
        }
    }

    private int deleteTransactionRowsByStoreIds(List<UUID> storeIds, String col) {
        if (storeIds == null || storeIds.isEmpty()) return 0;
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                out[0] = execute(conn, "DELETE FROM transactions WHERE " + col + " IN (" + placeholders + ")",
                        storeIds.stream().map(x -> (Object) x).toList());
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: transactions delete skipped (col={}, {})", col, e.getMessage());
            return 0;
        }
    }

    private static int execute(java.sql.Connection conn, String sql, List<Object> params) {
        java.sql.Savepoint sp = null;
        try {
            if (!conn.getAutoCommit()) sp = conn.setSavepoint();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                return ps.executeUpdate();
            }
        } catch (RuntimeException e) {
            if (sp != null) { try { conn.rollback(sp); } catch (Exception ignore) {} }
            throw e;
        } catch (Exception e) {
            if (sp != null) { try { conn.rollback(sp); } catch (Exception ignore) {} }
            throw new RuntimeException(e);
        }
    }

    private int deleteConnectedStoresByPrefix(String stripePrefix) {
        try {
            Session session = em.unwrap(Session.class);
            int[] out = new int[1];
            session.doWork(conn -> {
                List<UUID> storeIds = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM stores WHERE stripe_connect_id LIKE ?")) {
                    ps.setString(1, stripePrefix + "%");
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) storeIds.add(UUID.fromString(rs.getString(1)));
                    }
                }
                int total = 0;
                if (!storeIds.isEmpty()) {
                    String placeholders = String.join(",", storeIds.stream().map(x -> "?").toList());
                    List<Object> p = storeIds.stream().map(x -> (Object) x).toList();
                    total += execute(conn,
                            "DELETE FROM store_users WHERE store_id IN (" + placeholders + ") AND is_global_admin IS DISTINCT FROM TRUE",
                            p);
                    total += execute(conn, "DELETE FROM subscriptions WHERE store_id IN (" + placeholders + ")", p);
                    total += execute(conn, "DELETE FROM product_variants WHERE store_id IN (" + placeholders + ")", p);
                    total += execute(conn, "DELETE FROM stores WHERE id IN (" + placeholders + ")", p);
                }
                out[0] = total;
            });
            return out[0];
        } catch (Exception e) {
            log.warn("CatalogCleanupSeeder: {} cleanup skipped ({})", stripePrefix, e.getMessage());
            return 0;
        }
    }
}
