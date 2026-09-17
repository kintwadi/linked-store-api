package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.entity.Product;
import com.vicinity24.core.linkedstore.api.entity.ProductStatus;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.entity.SubscriptionStatus;
import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.entity.UserStatus;
import com.vicinity24.core.linkedstore.api.entity.VariantStatus;
import com.vicinity24.core.linkedstore.api.repository.ProductRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.service.PasswordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "seed", name = "catalog", havingValue = "true", matchIfMissing = true)
public class ProductCatalogSeeder {

    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordService passwordService;
    private final AuthProperties authProperties;

    @PersistenceContext
    private EntityManager em;

    @EventListener(ContextRefreshedEvent.class)
    @Transactional
    public void seedCatalog() {
        em.createNativeQuery("DELETE FROM inventory_locks").executeUpdate();
        em.createNativeQuery("DELETE FROM qr_tokens").executeUpdate();
        em.createNativeQuery("DELETE FROM transaction_items").executeUpdate();
        em.createNativeQuery("DELETE FROM transactions").executeUpdate();

        runDdlSilently("""
            ALTER TABLE qr_tokens
            ADD COLUMN IF NOT EXISTS fallback_code VARCHAR(16)
            """);
        runDdlSilently("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_qr_fallback_code
            ON qr_tokens (fallback_code)
            """);
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS email VARCHAR(255)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS password_salt VARCHAR(128)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(512)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(512)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS is_global_admin BOOLEAN NOT NULL DEFAULT FALSE");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE'");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN role TYPE VARCHAR(50)");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN name TYPE VARCHAR(255)");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN store_id DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN pin_hash DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN api_key_hash DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN status DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN email DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN password_salt DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN password_hash DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN name DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN role DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN is_global_admin DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN phone_number DROP NOT NULL");
        runDdlSilently("CREATE UNIQUE INDEX IF NOT EXISTS idx_store_users_email ON store_users(email) WHERE email IS NOT NULL");

        seedGlobalAdminUser();

        var storeDowntown = ensureStore("store-downtown-01",
                "Kicks & Co. — Downtown Flagship",
                new BigDecimal("40.712776"), new BigDecimal("-74.005974"),
                "US", "USD",
                "acct_1UFGaNGVibqsNijL",
                "https://images.unsplash.com/photo-1556906781-9a412961c28c?auto=format&fit=crop&w=400&h=400&q=80",
                "https://images.unsplash.com/photo-1514996937319-344454492b37?auto=format&fit=crop&w=1400&h=700&q=80",
                "120 Broadway, Manhattan, New York, NY",
                "10271");
        var storeUptown = ensureStore("store-uptown-02",
                "Sole District — Uptown",
                new BigDecimal("40.783060"), new BigDecimal("-73.971249"),
                "US", "USD",
                "acct_1UFHDaGbVVxOVEbj",
                "https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=400&h=400&q=80",
                "https://images.unsplash.com/photo-1595950653106-6c9ebd614d3a?auto=format&fit=crop&w=1400&h=700&q=80",
                "745 Fifth Ave, Manhattan, New York, NY",
                "10150");
        var storeBrooklyn = ensureStore("store-brooklyn-03",
                "Brooklyn Runs — Williamsburg",
                new BigDecimal("40.708978"), new BigDecimal("-73.956555"),
                "US", "USD",
                "acct_1UFGaNGjyrXTQ6F6",
                "https://images.unsplash.com/photo-1600185365483-26d7a4cc7519?auto=format&fit=crop&w=400&h=400&q=80",
                "https://images.unsplash.com/photo-1556905055-8f358a7a47b2?auto=format&fit=crop&w=1400&h=700&q=80",
                "186 Bedford Ave, Brooklyn, NY",
                "11249");
        log.info("CatalogSeeder: stores=[{}, {}, {}]", storeDowntown.getBusinessName(),
                storeUptown.getBusinessName(), storeBrooklyn.getBusinessName());

        Product airmax = ensureProduct("prd-airmax-pulse",
                "Nike Air Max Pulse — Running",
                "Cushioned daily runner with breathable knit upper, responsive Air Max heel unit, durable rubber outsole.",
                Map.of("category", "Footwear", "brand", "Nike", "gender", "Unisex",
                        "sport", "Running", "color", "White / Crimson"),
                "https://images.unsplash.com/photo-1606107557195-0e29a4b5b4aa?auto=format&fit=crop&w=900&h=900&q=80",
                "https://images.unsplash.com/photo-1606107557195-0e29a4b5b4aa?auto=format&fit=crop&w=300&h=300&q=70");
        seedVariants(airmax, List.of(
                v(storeDowntown, "airmax-pulse-wht-42", 9800, 14900, 7,
                        Map.of("size", "EU 42", "color", "White / Crimson"),
                        "https://images.unsplash.com/photo-1606107557195-0e29a4b5b4aa?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeDowntown, "airmax-pulse-wht-44", 9800, 14900, 3,
                        Map.of("size", "EU 44", "color", "White / Crimson"),
                        "https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeUptown,   "airmax-pulse-wht-43", 9800, 15500, 4,
                        Map.of("size", "EU 43", "color", "White / Crimson"),
                        "https://images.unsplash.com/photo-1539185441755-769473a23570?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeBrooklyn, "airmax-pulse-wht-41", 9800, 14900, 2,
                        Map.of("size", "EU 41", "color", "White / Crimson"),
                        "https://images.unsplash.com/photo-1600185365483-26d7a4cc7519?auto=format&fit=crop&w=600&h=600&q=80")
        ));

        Product af1 = ensureProduct("prd-af1-low",
                "Nike Air Force 1 Low — White on White",
                "Classic leather low-top with cupsole construction. Timeless silhouette that works with every outfit.",
                Map.of("category", "Footwear", "brand", "Nike", "gender", "Unisex",
                        "sport", "Lifestyle", "color", "Triple White"),
                "https://images.unsplash.com/photo-1600269452121-4f2416e55c28?auto=format&fit=crop&w=900&h=900&q=80",
                "https://images.unsplash.com/photo-1600269452121-4f2416e55c28?auto=format&fit=crop&w=300&h=300&q=70");
        seedVariants(af1, List.of(
                v(storeDowntown, "af1-ww-42", 7800, 11500, 12, Map.of("size", "EU 42", "color", "Triple White"),
                        "https://images.unsplash.com/photo-1600269452121-4f2416e55c28?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeDowntown, "af1-ww-43", 7800, 11500, 8,  Map.of("size", "EU 43", "color", "Triple White"),
                        "https://images.unsplash.com/photo-1595950653106-6c9ebd614d3a?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeUptown,   "af1-ww-44", 7800, 11900, 5,  Map.of("size", "EU 44", "color", "Triple White"),
                        "https://images.unsplash.com/photo-1551107696-a4b0c5a0d9a2?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeBrooklyn, "af1-ww-40", 7800, 11500, 9,  Map.of("size", "EU 40", "color", "Triple White"),
                        "https://images.unsplash.com/photo-1514989940723-e8e51635b782?auto=format&fit=crop&w=600&h=600&q=80")
        ));

        Product ultraboost = ensureProduct("prd-ultraboost-light",
                "Adidas Ultraboost Light",
                "Lightweight Boost foam running shoe with Primeknit+ upper, Continental rubber outsole.",
                Map.of("category", "Footwear", "brand", "Adidas", "gender", "Unisex",
                        "sport", "Running", "color", "Halo Silver"),
                "https://images.unsplash.com/photo-1556906781-9a412961c28c?auto=format&fit=crop&w=900&h=900&q=80",
                "https://images.unsplash.com/photo-1556906781-9a412961c28c?auto=format&fit=crop&w=300&h=300&q=70");
        seedVariants(ultraboost, List.of(
                v(storeUptown,   "ub-light-slv-42", 12800, 18900, 6, Map.of("size", "EU 42", "color", "Halo Silver"),
                        "https://images.unsplash.com/photo-1556906781-9a412961c28c?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeBrooklyn, "ub-light-slv-43", 12800, 18900, 3, Map.of("size", "EU 43", "color", "Halo Silver"),
                        "https://images.unsplash.com/photo-1595341888016-a392ef81b7de?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeBrooklyn, "ub-light-slv-44", 12800, 19500, 2, Map.of("size", "EU 44", "color", "Halo Silver"),
                        "https://images.unsplash.com/photo-1551107696-a4b0c5a0d9a2?auto=format&fit=crop&w=600&h=600&q=80")
        ));

        Product techfleece = ensureProduct("prd-techfleece-windrunner",
                "Nike Tech Fleece Windrunner",
                "Signature chevron zip hoodie in double-faced Tech Fleece. Slim fit, warm yet breathable.",
                Map.of("category", "Apparel", "brand", "Nike", "gender", "Unisex",
                        "type", "Hoodie", "color", "Black"),
                "https://images.unsplash.com/photo-1556821840-3a63f95609a7?auto=format&fit=crop&w=900&h=900&q=80",
                "https://images.unsplash.com/photo-1556821840-3a63f95609a7?auto=format&fit=crop&w=300&h=300&q=70");
        seedVariants(techfleece, List.of(
                v(storeDowntown, "tf-wind-blk-M",  8500, 13900, 10, Map.of("size", "M", "color", "Black"),
                        "https://images.unsplash.com/photo-1556821840-3a63f95609a7?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeDowntown, "tf-wind-blk-L",  8500, 13900, 7,  Map.of("size", "L", "color", "Black"),
                        "https://images.unsplash.com/photo-1620799140408-edc6dcb6d633?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeUptown,   "tf-wind-blk-XL", 8500, 14500, 4, Map.of("size", "XL", "color", "Black"),
                        "https://images.unsplash.com/photo-1618354691373-d851c5c3a990?auto=format&fit=crop&w=600&h=600&q=80")
        ));

        Product yankeesCap = ensureProduct("prd-newera-940-yankees",
                "New Era 9FORTY — MLB Yankees",
                "Adjustable cotton twill cap with stitched NY logo, curved brim.",
                Map.of("category", "Accessories", "brand", "New Era", "gender", "Unisex",
                        "type", "Cap", "color", "Navy"),
                "https://images.unsplash.com/photo-1588850561407-ed78c282e89b?auto=format&fit=crop&w=900&h=900&q=80",
                "https://images.unsplash.com/photo-1588850561407-ed78c282e89b?auto=format&fit=crop&w=300&h=300&q=70");
        seedVariants(yankeesCap, List.of(
                v(storeDowntown, "ne-940-ny-navy", 2000, 3200, 18,
                        Map.of("size", "Adjustable", "color", "Navy"),
                        "https://images.unsplash.com/photo-1588850561407-ed78c282e89b?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeBrooklyn, "ne-940-ny-navy-bk", 2000, 3200, 6,
                        Map.of("size", "Adjustable", "color", "Navy"),
                        "https://images.unsplash.com/photo-1521369909029-2afed882baee?auto=format&fit=crop&w=600&h=600&q=80")
        ));

        Product crewSocks = ensureProduct("prd-nike-everyday-plus-3p",
                "Nike Everyday Plus Crew — 3 Pack",
                "Breathable Dri-FIT cotton crew socks with arch band, 3-pack in grey/black/white.",
                Map.of("category", "Accessories", "brand", "Nike", "gender", "Unisex",
                        "type", "Socks", "color", "Multi"),
                "https://images.unsplash.com/photo-1617137968427-85924c800a22?auto=format&fit=crop&w=900&h=900&q=80",
                "https://images.unsplash.com/photo-1617137968427-85924c800a22?auto=format&fit=crop&w=300&h=300&q=70");
        seedVariants(crewSocks, List.of(
                v(storeDowntown, "nk-sock-3p-M", 980,  1600, 30, Map.of("size", "M (EU 38–42)", "color", "Multi"),
                        "https://images.unsplash.com/photo-1617137968427-85924c800a22?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeUptown,   "nk-sock-3p-L", 980,  1700, 22, Map.of("size", "L (EU 42–46)", "color", "Multi"),
                        "https://images.unsplash.com/photo-1631541909061-71e349d1f203?auto=format&fit=crop&w=600&h=600&q=80"),
                v(storeBrooklyn, "nk-sock-3p-XL",980,  1800, 10, Map.of("size", "XL (EU 46–50)", "color", "Multi"),
                        "https://images.unsplash.com/photo-1586350977771-b3b0abd50c82?auto=format&fit=crop&w=600&h=600&q=80")
        ));

        storeRepository.deleteByStripeConnectIdStartingWith("acct_connected_");

        int backfilled = 0;
        for (Store s : storeRepository.findAll()) {
            if (s.getGatewayCode() == null || s.getGatewayCode().isBlank()) {
                s.setGatewayCode(generateUniqueGatewayCode(storeRepository));
                storeRepository.save(s);
                backfilled++;
            }
        }
        if (backfilled > 0) {
            log.info("CatalogSeeder: backfilled gateway_code on {} previously-created stores", backfilled);
        }

        int totalVariants = (int) variantRepository.count();
        int totalProducts = (int) productRepository.count();
        int totalStores = (int) storeRepository.count();
        log.info("CatalogSeeder: seed complete — stores={} products={} variants={}",
                totalStores, totalProducts, totalVariants);
    }

    private Store ensureStore(String externalRef, String businessName, BigDecimal lat, BigDecimal lng,
                              String countryCode, String currencyCode,
                              String stripeConnectId, String logoUrl, String heroImageUrl,
                              String address, String postalCode) {
        String effectiveAddress = (address == null || address.isBlank()) ? "General Delivery" : address;
        String effectivePostalCode = (postalCode == null || postalCode.isBlank()) ? "00000" : postalCode;
        List<Store> matches = storeRepository.findAllByBusinessName(businessName);
        Store chosen;
        if (matches.isEmpty()) {
            String code = generateUniqueGatewayCode(storeRepository);
            chosen = storeRepository.save(Store.builder()
                    .businessName(businessName)
                    .latitude(lat)
                    .longitude(lng)
                    .countryCode(countryCode)
                    .currencyCode(currencyCode)
                    .stripeConnectId(stripeConnectId)
                    .subscriptionStatus(SubscriptionStatus.ACTIVE)
                    .logoUrl(logoUrl)
                    .heroImageUrl(heroImageUrl)
                    .address(effectiveAddress)
                    .postalCode(effectivePostalCode)
                    .gatewayCode(code)
                    .build());
        } else {
            chosen = matches.stream()
                    .filter(s -> s.getStripeConnectId() != null
                            && s.getStripeConnectId().startsWith("acct_1"))
                    .findFirst()
                    .orElse(matches.get(0));
            if (matches.size() > 1) {
                for (Store dup : matches) {
                    if (!dup.getId().equals(chosen.getId())) {
                        storeRepository.deleteById(dup.getId());
                    }
                }
            }
            boolean dirty = false;
            if (chosen.getLatitude().compareTo(lat) != 0) { chosen.setLatitude(lat); dirty = true; }
            if (chosen.getLongitude().compareTo(lng) != 0) { chosen.setLongitude(lng); dirty = true; }
            if ((chosen.getCountryCode() == null || chosen.getCountryCode().isBlank()) && countryCode != null) {
                chosen.setCountryCode(countryCode); dirty = true;
            }
            if ((chosen.getCurrencyCode() == null || chosen.getCurrencyCode().isBlank()) && currencyCode != null) {
                chosen.setCurrencyCode(currencyCode); dirty = true;
            }
            if ((chosen.getLogoUrl() == null || chosen.getLogoUrl().isBlank()) && logoUrl != null) {
                chosen.setLogoUrl(logoUrl); dirty = true;
            }
            if ((chosen.getHeroImageUrl() == null || chosen.getHeroImageUrl().isBlank()) && heroImageUrl != null) {
                chosen.setHeroImageUrl(heroImageUrl); dirty = true;
            }
            if ((chosen.getAddress() == null || chosen.getAddress().isBlank()) && effectiveAddress != null) {
                chosen.setAddress(effectiveAddress); dirty = true;
            }
            if ((chosen.getPostalCode() == null || chosen.getPostalCode().isBlank()) && effectivePostalCode != null) {
                chosen.setPostalCode(effectivePostalCode); dirty = true;
            }
            if (chosen.getSubscriptionStatus() == null) {
                chosen.setSubscriptionStatus(SubscriptionStatus.ACTIVE); dirty = true;
            }
            if (stripeConnectId != null && !stripeConnectId.isBlank()
                    && (chosen.getStripeConnectId() == null
                        || chosen.getStripeConnectId().startsWith("acct_connected_"))) {
                chosen.setStripeConnectId(stripeConnectId); dirty = true;
            }
            if (chosen.getGatewayCode() == null || chosen.getGatewayCode().isBlank()) {
                chosen.setGatewayCode(generateUniqueGatewayCode(storeRepository));
                dirty = true;
            }
            if (dirty) chosen = storeRepository.save(chosen);
        }
        return chosen;
    }

    private static String generateUniqueGatewayCode(StoreRepository storeRepository) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = Store.generateGatewayCode();
            if (!storeRepository.existsByGatewayCode(candidate)) {
                return candidate;
            }
        }
        return String.format("%014d", System.nanoTime() % 100000000000000L);
    }

    private Product ensureProduct(String skuStem, String title, String description,
                                  Map<String, Object> attributes, String primary, String thumbnail) {
        var byTitle = productRepository.findAllByStatus(ProductStatus.ACTIVE).stream()
                .filter(p -> title.equals(p.getTitle()))
                .findFirst();
        if (byTitle.isPresent()) return byTitle.get();
        return productRepository.save(Product.builder()
                .title(title)
                .description(description)
                .attributes(attributes)
                .status(ProductStatus.ACTIVE)
                .primaryImageUrl(primary)
                .thumbnailUrl(thumbnail)
                .build());
    }

    private VariantSeed v(Store store, String sku, int wholesaleCents, int retailCents, int stock,
                          Map<String, Object> variantAttributes, String imageUrl) {
        return new VariantSeed(store.getId(), sku, wholesaleCents, retailCents, stock, variantAttributes, imageUrl);
    }

    private void seedVariants(Product product, List<VariantSeed> variants) {
        UUID productId = product.getId();
        for (var vs : variants) {
            variantRepository.findBySku(vs.sku()).ifPresentOrElse(existing -> {
                boolean dirty = false;
                if (!existing.getProductId().equals(productId)) { existing.setProductId(productId); dirty = true; }
                if (!existing.getStoreId().equals(vs.storeId())) { existing.setStoreId(vs.storeId()); dirty = true; }
                if (!existing.getWholesalePriceCents().equals(vs.wholesale())) { existing.setWholesalePriceCents(vs.wholesale()); dirty = true; }
                if (!existing.getRetailPriceCents().equals(vs.retail())) { existing.setRetailPriceCents(vs.retail()); dirty = true; }
                if (!existing.getStockQuantity().equals(vs.stock())) { existing.setStockQuantity(vs.stock()); dirty = true; }
                if (!existing.getVariantAttributes().equals(vs.attributes())) { existing.setVariantAttributes(vs.attributes()); dirty = true; }
                if ((existing.getImageUrl() == null || existing.getImageUrl().isBlank()) && vs.imageUrl() != null) {
                    existing.setImageUrl(vs.imageUrl()); dirty = true;
                }
                if (existing.getStatus() == null) { existing.setStatus(VariantStatus.ACTIVE); dirty = true; }
                if (dirty) variantRepository.save(existing);
            }, () -> variantRepository.save(ProductVariant.builder()
                    .productId(productId)
                    .storeId(vs.storeId())
                    .sku(vs.sku())
                    .wholesalePriceCents(vs.wholesale())
                    .retailPriceCents(vs.retail())
                    .stockQuantity(vs.stock())
                    .variantAttributes(vs.attributes())
                    .status(VariantStatus.ACTIVE)
                    .imageUrl(vs.imageUrl())
                    .build()));
        }
    }

    private record VariantSeed(UUID storeId, String sku, int wholesale, int retail, int stock,
                               Map<String, Object> attributes, String imageUrl) {}

    private void runDdlSilently(String sql) {
        try {
            em.createNativeQuery(sql).executeUpdate();
        } catch (Exception e) {
            log.debug("DDL no-op (already exists or unsupported): {}", e.getMessage());
        }
    }

    private void seedGlobalAdminUser() {
        try {
            List<UserAccount> existing = userAccountRepository.findGlobalAdmins();
            if (existing != null && !existing.isEmpty()) {
                log.info("Global admin users already exist: {}. Skip seeding.", existing.size());
                return;
            }
        } catch (Exception e) {
            log.warn("Cannot query global admins table (likely not yet created): {}", e.getMessage());
            return;
        }
        String email = authProperties.getDefaultAdminEmail() != null
                ? authProperties.getDefaultAdminEmail() : "admin@linked.store";
        String password = authProperties.getDefaultAdminPassword() != null
                ? authProperties.getDefaultAdminPassword() : "Admin123!";
        String salt = passwordService.generateSalt();
        String hash = passwordService.hash(password, salt);
        UserAccount admin = UserAccount.builder()
                .name("Global Admin")
                .email(email)
                .passwordSalt(salt)
                .passwordHash(hash)
                .role(UserRole.GLOBAL_ADMIN)
                .globalAdmin(true)
                .status(UserStatus.ACTIVE)
                .build();
        admin = userAccountRepository.save(admin);
        log.info("Seeded global admin user: email={} id={}", email, admin.getId());
    }
}
