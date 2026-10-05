package com.vicinity24.core.linkedstore.api.automatic.product.load.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadReport;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import com.vicinity24.core.linkedstore.api.automatic.product.load.parsers.CsvProductParser;
import com.vicinity24.core.linkedstore.api.automatic.product.load.parsers.JsonProductParser;
import com.vicinity24.core.linkedstore.api.automatic.product.load.parsers.XmlProductParser;
import com.vicinity24.core.linkedstore.api.entity.*;
import com.vicinity24.core.linkedstore.api.repository.ProductRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutomaticProductLoadService {

    private final JsonProductParser jsonParser;
    private final CsvProductParser csvParser;
    private final XmlProductParser xmlParser;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StoreRepository storeRepository;
    private final ProductImageResolver imageResolver;

    @Qualifier("productUploadJsonMapper")
    private final JsonMapper jsonMapper;

    @PersistenceContext
    private final EntityManager em;

    public String detectFormat(String filename, MultipartFile file) {
        String fn = (filename == null) ? "" : filename.trim().toLowerCase(Locale.ROOT);
        if (fn.endsWith(".json")) return "json";
        if (fn.endsWith(".csv")) return "csv";
        if (fn.endsWith(".xml")) return "xml";

        // Fallback: sniff first non-whitespace byte.
        try (var bis = new BufferedInputStream(file.getInputStream())) {
            bis.mark(64);
            int b;
            while ((b = bis.read()) != -1) {
                if (Character.isWhitespace(b)) continue;
                if (b == '<') return "xml";
                if (b == '{' || b == '[') return "json";
                // Otherwise assume CSV (comma first, BOM, digits, quotes...)
                return "csv";
            }
        } catch (Exception ignore) { /* fallthrough */ }
        return "csv";
    }

    public List<ProductLoadRequest> parse(MultipartFile file, String format) throws Exception {
        return switch (format) {
            case "json" -> jsonParser.parse(file.getInputStream());
            case "xml"  -> xmlParser.parse(file.getInputStream());
            case "csv"  -> csvParser.parse(file.getInputStream());
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    }

    @Transactional
    public ProductLoadReport loadForStore(UUID storeId, List<ProductLoadRequest> rows, String format) {
        Store store = storeRepository.findById(storeId).orElseThrow(
                () -> new IllegalArgumentException("Store " + storeId + " does not exist."));

        ProductLoadReport report = ProductLoadReport.builder()
                .generatedAt(OffsetDateTime.now())
                .storeId(store.getId())
                .storeName(store.getBusinessName())
                .format(format)
                .rowsParsed(rows.size())
                .build();
        List<String> warnings = new ArrayList<>();
        List<ProductLoadReport.RowError> errors = new ArrayList<>();
        List<String> highlights = new ArrayList<>();
        report.setWarningMessages(warnings);
        report.setRowErrors(errors);
        report.setSummaryHighlights(highlights);

        int imgCopied = 0, imgSkipped = 0;

        for (int i = 0; i < rows.size(); i++) {
            ProductLoadRequest row = rows.get(i);
            try {
                if (row == null) continue;
                if ((row.getTitle() == null || row.getTitle().isBlank()) && (row.getSku() == null || row.getSku().isBlank())) {
                    report.setProductsSkipped(report.getProductsSkipped() + 1);
                    report.setVariantsSkipped(report.getVariantsSkipped() + 1);
                    warnings.add("Row " + (i + 1) + " skipped — no title and no SKU provided.");
                    continue;
                }

                Product p = ensureProduct(row, report);
                ProductVariant v = ensureVariant(store, p, row, report, i);

                // ------------ Images ------------
                String ns = (p.getTitle() == null ? "product" : p.getTitle()) + "_" + i;
                ProductImageResolver.CopyResult primary = imageResolver.resolveAndCopy(row.getPrimaryImage(), ns + "_primary");
                ProductImageResolver.CopyResult thumb   = imageResolver.resolveAndCopy(row.getThumbnailImage(), ns + "_thumb");
                String variantImgFile = firstNonBlank(row.getVariantImage(), row.getPrimaryImage());
                ProductImageResolver.CopyResult variantImg = imageResolver.resolveAndCopy(variantImgFile, ns + "_var");

                if (primary.copied()) imgCopied++; else imgSkipped++;
                if (thumb.copied()) imgCopied++; else imgSkipped++;
                if (variantImg.copied()) imgCopied++; else imgSkipped++;

                List<String> galleryUrls = new ArrayList<>();
                List<String> gal = row.getGalleryImages() == null ? List.of() : row.getGalleryImages();
                for (int g = 0; g < gal.size(); g++) {
                    ProductImageResolver.CopyResult gr = imageResolver.resolveAndCopy(gal.get(g), ns + "_g" + g);
                    if (gr.url() != null) galleryUrls.add(gr.url());
                    if (gr.copied()) imgCopied++; else imgSkipped++;
                }

                boolean productDirty = false;
                if (primary.url() != null && !primary.url().equals(p.getPrimaryImageUrl())) {
                    p.setPrimaryImageUrl(primary.url()); productDirty = true;
                }
                if (thumb.url() != null && !thumb.url().equals(p.getThumbnailUrl())) {
                    p.setThumbnailUrl(thumb.url()); productDirty = true;
                }
                if (!galleryUrls.isEmpty()) {
                    // If new gallery provided → replace; otherwise leave existing untouched.
                    List<String> existing = p.getGalleryImageUrls();
                    List<String> merged = galleryUrls;
                    if (!merged.equals(existing)) {
                        p.setGalleryImageUrls(new ArrayList<>(merged));
                        productDirty = true;
                    }
                }
                if (productDirty) {
                    productRepository.save(p);
                }

                boolean variantDirty = false;
                if (variantImg.url() != null && !variantImg.url().equals(v.getImageUrl())) {
                    v.setImageUrl(variantImg.url()); variantDirty = true;
                }
                if (variantDirty) {
                    variantRepository.save(v);
                }
            } catch (Exception ex) {
                log.warn("AutomaticProductLoadService: row {} failed for store {}", i + 1, storeId, ex);
                errors.add(ProductLoadReport.RowError.builder()
                        .row(i + 1)
                        .title(row == null ? null : row.getTitle())
                        .sku(row == null ? null : row.getSku())
                        .message(trim(ex.getMessage(), 200))
                        .build());
                report.setErrors(report.getErrors() + 1);
            }
        }

        report.setImagesCopied(imgCopied);
        report.setImagesSkipped(imgSkipped);

        highlights.add(String.format(
                "Catalog: %d new products created, %d updated, %d skipped.",
                report.getProductsCreated(), report.getProductsUpdated(), report.getProductsSkipped()));
        highlights.add(String.format(
                "Pricing & stock: %d new variants created, %d updated, %d skipped.",
                report.getVariantsCreated(), report.getVariantsUpdated(), report.getVariantsSkipped()));
        if (imgCopied > 0 || imgSkipped > 0) {
            highlights.add(String.format(
                    "Images from frontend/products: %d new assets imported, %d reused (already cached).",
                    imgCopied, imgSkipped));
        }
        if (report.getErrors() > 0) {
            highlights.add(String.format(
                    "%d row(s) failed to import — see rowErrors for details.", report.getErrors()));
        }
        return report;
    }

    // =========== Internals ===========

    private Product ensureProduct(ProductLoadRequest row, ProductLoadReport report) {
        String title = (row.getTitle() == null || row.getTitle().isBlank())
                ? (row.getSku() == null ? "Untitled product" : "Product for SKU " + row.getSku())
                : row.getTitle().trim();

        Optional<Product> byTitle = productRepository.findByTitle(title);
        Product product;
        boolean created = false;
        if (byTitle.isPresent()) {
            product = byTitle.get();
            applyProductUpdates(product, row, false);
            report.setProductsUpdated(report.getProductsUpdated() + 1);
        } else {
            product = Product.builder()
                    .title(title)
                    .status(ProductStatus.ACTIVE)
                    .attributes(new HashMap<>())
                    .galleryImageUrls(new ArrayList<>())
                    .build();
            applyProductUpdates(product, row, true);
            product = productRepository.save(product);
            created = true;
            report.setProductsCreated(report.getProductsCreated() + 1);
        }
        em.flush();
        return product;
    }

    private void applyProductUpdates(Product p, ProductLoadRequest row, boolean created) {
        String desc = row.getDescription() == null ? null : row.getDescription().trim();
        if (!created && desc != null && !desc.isBlank()) p.setDescription(desc);
        if (created && desc != null) p.setDescription(desc);

        ProductStatus desiredStatus = parseStatus(row.getStatus());
        if (desiredStatus != null) p.setStatus(desiredStatus);

        Map<String, Object> attrs = new HashMap<>(p.getAttributes() == null ? new HashMap<>() : p.getAttributes());
        if (row.getAttributesJson() != null && !row.getAttributesJson().isBlank()) {
            try {
                Map<String, Object> parsed = jsonMapper.readValue(row.getAttributesJson(), new TypeReference<>() {});
                if (parsed != null) attrs.putAll(parsed);
            } catch (Exception e) {
                log.warn("AutomaticProductLoadService: invalid attributesJson for product {} — skipping merge", p.getTitle(), e);
            }
        }
        mergeKeyValue(attrs, "category", row.getCategory());
        mergeKeyValue(attrs, "brand", row.getBrand());
        mergeKeyValue(attrs, "gender", row.getGender());
        mergeKeyValue(attrs, "sport", row.getSport());
        mergeKeyValue(attrs, "color", row.getColor());
        if (row.getExtraColumns() != null) {
            for (var e : row.getExtraColumns().entrySet()) {
                if (e.getValue() == null) continue;
                mergeKeyValue(attrs, e.getKey(), String.valueOf(e.getValue()));
            }
        }
        p.setAttributes(attrs);
    }

    private ProductVariant ensureVariant(Store store, Product product, ProductLoadRequest row, ProductLoadReport report, int rowIndex) {
        int retail = requirePositive(parseMoney(row.getRetailPrice()), "retailPrice", rowIndex, report);
        int wholesale = requireNonNegative(parseMoney(row.getWholesalePrice()), "wholesalePrice", rowIndex, report);
        int stock = parseNonNegativeInt(row.getStockQuantity());

        VariantStatus vStatus = parseVariantStatus(row.getStatus());
        if (vStatus == null) vStatus = VariantStatus.ACTIVE;

        String sku = (row.getSku() == null || row.getSku().isBlank())
                ? "auto-" + product.getId() + "-" + store.getId()
                : row.getSku().trim();

        Optional<ProductVariant> bySku = variantRepository.findBySku(sku);
        ProductVariant variant;
        boolean created;
        if (bySku.isPresent()) {
            variant = bySku.get();
            if (!variant.getStoreId().equals(store.getId())) {
                // SKU already belongs to a different store — fall back to a new auto-SKU so we don't mutate other stores' inventory.
                report.getWarningMessages().add("Row " + (rowIndex + 1) + ": SKU '" + sku + "' is owned by another store — generated a new SKU for store '" + store.getBusinessName() + "'.");
                sku = "auto-" + product.getId() + "-" + store.getId();
                variant = null;
                created = true;
            } else {
                created = false;
            }
        } else {
            variant = null;
            created = true;
        }

        if (variant == null) {
            // Scan for an existing variant for this (storeId, productId) pair with no SKU — if present, reuse & SKU override.
            List<ProductVariant> existingForStore = variantRepository.findByProductId(product.getId()).stream()
                    .filter(pv -> pv.getStoreId().equals(store.getId())).toList();
            if (!existingForStore.isEmpty()) {
                variant = existingForStore.get(0);
                created = false;
            } else {
                variant = ProductVariant.builder()
                        .productId(product.getId())
                        .storeId(store.getId())
                        .stockQuantity(0)
                        .variantAttributes(new HashMap<>())
                        .galleryImageUrls(new ArrayList<>())
                        .status(VariantStatus.ACTIVE)
                        .build();
                created = true;
            }
        }

        variant.setSku(sku);
        variant.setRetailPriceCents(retail);
        variant.setWholesalePriceCents(wholesale);
        variant.setStockQuantity(stock);
        variant.setStatus(vStatus);

        Map<String, Object> vAttrs = new HashMap<>(variant.getVariantAttributes() == null ? new HashMap<>() : variant.getVariantAttributes());
        if (row.getVariantAttributesJson() != null && !row.getVariantAttributesJson().isBlank()) {
            try {
                Map<String, Object> parsed = jsonMapper.readValue(row.getVariantAttributesJson(), new TypeReference<>() {});
                if (parsed != null) vAttrs.putAll(parsed);
            } catch (Exception e) {
                log.warn("AutomaticProductLoadService: invalid variantAttributesJson for sku {} — skipping merge", sku, e);
            }
        }
        mergeKeyValue(vAttrs, "color", row.getColor());
        mergeKeyValue(vAttrs, "size", row.getSize());
        variant.setVariantAttributes(vAttrs);

        // Flush + save to bump version correctly (Hibernate @Version).
        ProductVariant saved = variantRepository.save(variant);

        if (created) report.setVariantsCreated(report.getVariantsCreated() + 1);
        else report.setVariantsUpdated(report.getVariantsUpdated() + 1);

        em.flush();
        return saved;
    }

    // ========= Helpers =========

    private static void mergeKeyValue(Map<String, Object> m, String key, String value) {
        if (key == null || key.isBlank()) return;
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) return;
        // Don't overwrite already-populated values on UPDATE — user can always reset via the Add New Product form.
        Object existing = m.get(key);
        if (existing != null && !String.valueOf(existing).isBlank()) return;
        m.put(key, v);
    }

    private static ProductStatus parseStatus(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "ENABLED", "ON", "LIVE", "PUBLISHED" -> ProductStatus.ACTIVE;
            case "DRAFT", "HIDDEN", "OFF" -> ProductStatus.DRAFT;
            case "PENDING", "PENDING_REVIEW" -> ProductStatus.PENDING;
            case "OUT_OF_STOCK" -> ProductStatus.OUT_OF_STOCK;
            case "INACTIVE", "DISABLED", "ARCHIVED" -> ProductStatus.INACTIVE;
            default -> null;
        };
    }

    private static VariantStatus parseVariantStatus(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "ENABLED", "ON", "LIVE", "PUBLISHED" -> VariantStatus.ACTIVE;
            case "INACTIVE", "DISABLED", "ARCHIVED" -> VariantStatus.INACTIVE;
            case "OUT_OF_STOCK" -> VariantStatus.OUT_OF_STOCK;
            case "DRAFT", "HIDDEN", "OFF" -> VariantStatus.DRAFT;
            case "PENDING" -> VariantStatus.PENDING;
            case "DISABLED_ALT", "DISABLED2" -> VariantStatus.DISABLED;
            default -> null;
        };
    }

    /**
     * Accepts both dollars ("149.00", "149,00") and cents (integer string "14900").
     * Always returns integer number of cents (never null/zero — the DB column is
     * NOT NULL so falling back to 0 when blank).
     */
    private static Integer parseMoney(String s) {
        if (s == null) return 0;
        String v = s.trim();
        if (v.isEmpty()) return 0;
        v = v.replaceAll("[\\s$€£¥₹]", "").replace(',', '.');
        try {
            if (v.contains(".")) {
                double d = Double.parseDouble(v);
                return (int) Math.round(d * 100d);
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    private static int parseNonNegativeInt(String s) {
        if (s == null) return 0;
        String v = s.trim();
        if (v.isEmpty()) return 0;
        try {
            int i = Integer.parseInt(v);
            return Math.max(i, 0);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    private static int requirePositive(int cents, String field, int rowIdx, ProductLoadReport report) {
        if (cents <= 0) {
            report.getWarningMessages().add("Row " + (rowIdx + 1) + ": missing positive value for " + field + " — price recorded as 1 cent.");
            return 1;
        }
        return cents;
    }

    private static int requireNonNegative(int cents, String field, int rowIdx, ProductLoadReport report) {
        return Math.max(cents, 0);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
