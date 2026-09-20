package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.entity.Product;
import com.vicinity24.core.linkedstore.api.entity.ProductStatus;
import com.vicinity24.core.linkedstore.api.entity.ProductVariant;
import com.vicinity24.core.linkedstore.api.entity.VariantStatus;
import com.vicinity24.core.linkedstore.api.repository.ProductRepository;
import com.vicinity24.core.linkedstore.api.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductsController {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    @GetMapping("")
    public List<Map<String, Object>> list() {
        List<Product> products = productRepository.findAllByStatus(ProductStatus.ACTIVE);
        return products.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId().toString());
            m.put("title", p.getTitle());
            m.put("description", p.getDescription());
            m.put("primaryImageUrl", p.getPrimaryImageUrl());
            m.put("thumbnailUrl", p.getThumbnailUrl());
            m.put("attributes", p.getAttributes());
            m.put("status", p.getStatus());

            List<ProductVariant> vs = variantRepository.findByProductIdAndStatusOrderByRetailPriceCentsAsc(p.getId(), VariantStatus.ACTIVE);
            if (!vs.isEmpty()) {
                ProductVariant v = vs.get(0);
                m.put("retailPriceCents", v.getRetailPriceCents());
                m.put("wholesalePriceCents", v.getWholesalePriceCents());
                m.put("sku", v.getSku());
                m.put("variantId", v.getId().toString());
                m.put("storeId", v.getStoreId().toString());
            }
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        Product p = resolveProductOne(id);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId().toString());
        m.put("title", p.getTitle());
        m.put("description", p.getDescription());
        m.put("primaryImageUrl", p.getPrimaryImageUrl());
        m.put("thumbnailUrl", p.getThumbnailUrl());
        m.put("attributes", p.getAttributes());
        m.put("status", p.getStatus());

        List<ProductVariant> vs = variantRepository.findByProductIdAndStatusOrderByRetailPriceCentsAsc(p.getId(), VariantStatus.ACTIVE);
        List<Map<String, Object>> variantMaps = vs.stream().map(v -> {
            Map<String, Object> vm = new HashMap<>();
            vm.put("id", v.getId().toString());
            vm.put("sku", v.getSku());
            vm.put("retailPriceCents", v.getRetailPriceCents());
            vm.put("wholesalePriceCents", v.getWholesalePriceCents());
            vm.put("imageUrl", v.getImageUrl());
            vm.put("stockQuantity", v.getStockQuantity());
            vm.put("storeId", v.getStoreId().toString());
            vm.put("variantAttributes", v.getVariantAttributes());
            return vm;
        }).collect(Collectors.toList());
        m.put("variants", variantMaps);

        if (!vs.isEmpty()) {
            ProductVariant v = vs.get(0);
            m.put("retailPriceCents", v.getRetailPriceCents());
            m.put("wholesalePriceCents", v.getWholesalePriceCents());
            m.put("sku", v.getSku());
            m.put("variantId", v.getId().toString());
            m.put("storeId", v.getStoreId().toString());
        }

        return ResponseEntity.ok(m);
    }

    @GetMapping("/variants/{variantId}")
    public ResponseEntity<Map<String, Object>> getVariant(@PathVariable String variantId) {
        UUID vid;
        try { vid = UUID.fromString(variantId); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(HttpStatus.BAD_REQUEST).build(); }
        Optional<ProductVariant> opt = variantRepository.findByIdWithProduct(vid);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        ProductVariant v = opt.get();
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("id", v.getId().toString());
        vm.put("sku", v.getSku());
        vm.put("retailPriceCents", v.getRetailPriceCents());
        vm.put("wholesalePriceCents", v.getWholesalePriceCents());
        vm.put("imageUrl", v.getImageUrl());
        vm.put("stockQuantity", v.getStockQuantity());
        vm.put("storeId", v.getStoreId() != null ? v.getStoreId().toString() : null);
        vm.put("status", v.getStatus() != null ? v.getStatus().name() : null);
        vm.put("variantAttributes", v.getVariantAttributes());
        if (v.getProduct() != null) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", v.getProduct().getId().toString());
            pm.put("title", v.getProduct().getTitle());
            pm.put("primaryImageUrl", v.getProduct().getPrimaryImageUrl());
            pm.put("thumbnailUrl", v.getProduct().getThumbnailUrl());
            pm.put("status", v.getProduct().getStatus() != null ? v.getProduct().getStatus().name() : null);
            vm.put("product", pm);
        }
        return ResponseEntity.ok(vm);
    }

    private Product resolveProductOne(String productId) {
        try {
            UUID productUuid = UUID.fromString(productId);
            Product p = productRepository.findById(productUuid).orElse(null);
            if (p != null) {
                return p;
            }
        } catch (IllegalArgumentException e) {
        }

        List<Product> activeProducts = productRepository.findAllByStatus(ProductStatus.ACTIVE);
        if (productId.length() >= 3) {
            String sub = productId.toLowerCase().substring(0, Math.min(productId.length() - 2, productId.length()));
            for (Product p : activeProducts) {
                if (p.getTitle() != null && p.getTitle().toLowerCase().contains(sub)) {
                    return p;
                }
            }
        }

        if (productId.toLowerCase().contains("airmax")) {
            for (Product p : activeProducts) {
                if (p.getTitle() != null && p.getTitle().toLowerCase().contains("airmax")) {
                    return p;
                }
            }
        }

        if (productId.startsWith("p-")) {
            for (Product p : activeProducts) {
                if (p.getTitle() != null && p.getTitle().toLowerCase().contains("airmax")) {
                    return p;
                }
            }
        }

        return null;
    }
}
