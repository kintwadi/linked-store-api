package com.vicinity24.core.linkedstore.api.automatic.product.load.parsers;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.dataformat.csv.CsvGenerator;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CsvProductParser {

    private static final String[] PRESET_COLUMNS = {
            "title", "description", "sku", "retailPrice", "wholesalePrice", "stockQuantity",
            "status", "primaryImage", "thumbnailImage", "galleryImages", "variantImage",
            "category", "brand", "gender", "sport", "color", "size",
            "attributesJson", "variantAttributesJson"
    };

    @Qualifier("productUploadCsvMapper")
    private final CsvMapper csvMapper;

    /**
     * CSV input accepts both:
     * <ol>
     *   <li>A header row matching {@code PRESET_COLUMNS} → parsed directly into
     *       {@link ProductLoadRequest} using Jackson CSV schema with header.</li>
     *   <li>A header row with any column names (e.g. from a POS export) → the
     *       record is read as {@code Map<String,String>} and matched to fields
     *       via {@code JsonAlias} so POS exports work with zero transformation.</li>
     * </ol>
     *
     * <p>{@code galleryImages} in CSV is a pipe-separated ({@code |}) list,
     * matching common spreadsheet conventions for multi-value cells.</p>
     */
    public List<ProductLoadRequest> parse(InputStream in) throws Exception {
        List<ProductLoadRequest> out = new ArrayList<>();
        CsvSchema schema = CsvSchema.emptySchema().withHeader().withColumnSeparator(',');
        ObjectReader reader = csvMapper.readerFor(Map.class).with(schema);
        try (MappingIterator<Map<String, String>> it = reader.readValues(in)) {
            int rowIdx = 0;
            while (it.hasNext()) {
                rowIdx++;
                Map<String, String> row = it.next();
                ProductLoadRequest req = fromMap(row);
                if (req != null) {
                    out.add(req);
                }
            }
        }
        return out;
    }

    public String writeTemplate(List<ProductLoadRequest> samples) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        for (ProductLoadRequest r : samples) rows.add(toMap(r));
        CsvSchema.Builder sb = CsvSchema.builder().setUseHeader(true).setColumnSeparator(',');
        for (String c : PRESET_COLUMNS) sb.addColumn(c);
        CsvSchema schema = sb.build().withLineSeparator("\n").withQuoteChar('"');
        String body = csvMapper.writer(schema).writeValueAsString(rows);
        return "\uFEFF" + body;
    }

    @SuppressWarnings("unchecked")
    private ProductLoadRequest fromMap(Map<String, String> row) {
        if (row == null) return null;
        Map<String, String> lowered = new HashMap<>(row.size());
        for (var e : row.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT).replaceAll("[_ -]+", "");
            lowered.put(k, e.getValue());
        }
        if (allEmpty(lowered)) return null;
        ProductLoadRequest r = new ProductLoadRequest();
        r.setTitle(pick(lowered, "title", "name", "productname", "product_title", "label"));
        r.setDescription(pick(lowered, "description", "desc", "productdescription", "product_description", "body", "shortdescription"));
        r.setSku(pick(lowered, "sku", "sku_id", "sku_code", "productsku", "variantsku", "variant_sku"));
        r.setRetailPrice(pick(lowered, "retailprice", "retail_price", "price", "retail", "pricecents", "retailpricecents"));
        r.setWholesalePrice(pick(lowered, "wholesaleprice", "wholesale_price", "cost", "wholesale", "costprice", "wholesalepricecents"));
        r.setStockQuantity(pick(lowered, "stockquantity", "stock_quantity", "stock", "qty", "quantity", "inventory", "stocklevel"));
        r.setStatus(pick(lowered, "status", "variantstatus", "productstatus", "variant_status", "product_status"));
        r.setPrimaryImage(pick(lowered, "primaryimage", "primary_image", "image", "cover", "coverimage", "mainimage", "primaryimageurl", "productimage"));
        r.setThumbnailImage(pick(lowered, "thumbnailimage", "thumbnail_image", "thumb", "thumbnail", "thumbnailurl"));
        String gal = pick(lowered, "galleryimages", "gallery", "images", "gallery_image_urls", "galleryimageurls");
        if (gal != null && !gal.isBlank()) {
            List<String> g = new ArrayList<>();
            for (String piece : gal.split("[|;,]")) {
                String p = piece.trim();
                if (!p.isEmpty()) g.add(p);
            }
            r.setGalleryImages(g);
        } else {
            r.setGalleryImages(new ArrayList<>());
        }
        r.setVariantImage(pick(lowered, "variantimage", "variant_image", "variantimageurl", "variant_image_url", "variantimagefile"));
        r.setCategory(pick(lowered, "category", "productcategory", "product_category", "type", "producttype", "product_type"));
        r.setBrand(pick(lowered, "brand", "productbrand", "product_brand", "manufacturer"));
        r.setGender(pick(lowered, "gender", "productgender", "product_gender"));
        r.setSport(pick(lowered, "sport"));
        r.setColor(pick(lowered, "color", "productcolor", "product_color", "colour"));
        r.setSize(pick(lowered, "size", "variantsize", "variant_size"));
        r.setAttributesJson(pick(lowered, "attributesjson", "attributes_json", "attributes", "metadata", "productmetadata"));
        r.setVariantAttributesJson(pick(lowered, "variantattributesjson", "variantattributes", "variant_attributes", "variantmetadata"));
        Map<String, Object> extra = new HashMap<>();
        for (var e : row.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String k = e.getKey() == null ? "" : e.getKey().trim();
            if (recognized(k)) continue;
            extra.put(k, e.getValue());
        }
        if (!extra.isEmpty()) r.setExtraColumns(extra);
        return r;
    }

    private static boolean recognized(String key) {
        String n = key.toLowerCase(Locale.ROOT).replaceAll("[_ -]+", "");
        return switch (n) {
            case "title","name","productname","product_title","label",
                 "description","desc","productdescription","product_description","body","shortdescription",
                 "sku","sku_id","sku_code","productsku","variantsku","variant_sku",
                 "retailprice","retail_price","price","retail","pricecents","retailpricecents",
                 "wholesaleprice","wholesale_price","cost","wholesale","costprice","wholesalepricecents",
                 "stockquantity","stock_quantity","stock","qty","quantity","inventory","stocklevel",
                 "status","variantstatus","productstatus","variant_status","product_status",
                 "primaryimage","primary_image","image","cover","coverimage","mainimage","primaryimageurl","productimage",
                 "thumbnailimage","thumbnail_image","thumb","thumbnail","thumbnailurl",
                 "galleryimages","gallery","images","gallery_image_urls","galleryimageurls",
                 "variantimage","variant_image","variantimageurl","variant_image_url","variantimagefile",
                 "category","productcategory","product_category","type","producttype","product_type",
                 "brand","productbrand","product_brand","manufacturer",
                 "gender","productgender","product_gender",
                 "sport",
                 "color","productcolor","product_color","colour",
                 "size","variantsize","variant_size",
                 "attributesjson","attributes_json","attributes","metadata","productmetadata",
                 "variantattributesjson","variantattributes","variant_attributes","variantmetadata"
                    -> true;
            default -> false;
        };
    }

    private static boolean allEmpty(Map<String, String> m) {
        for (String v : m.values()) if (v != null && !v.isBlank()) return false;
        return true;
    }

    private static String pick(Map<String, String> lowered, String... keys) {
        for (String k : keys) {
            String v = lowered.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private Map<String, String> toMap(ProductLoadRequest r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : PRESET_COLUMNS) m.put(c, "");
        m.put("title", s(r.getTitle()));
        m.put("description", s(r.getDescription()));
        m.put("sku", s(r.getSku()));
        m.put("retailPrice", s(r.getRetailPrice()));
        m.put("wholesalePrice", s(r.getWholesalePrice()));
        m.put("stockQuantity", s(r.getStockQuantity()));
        m.put("status", s(r.getStatus()));
        m.put("primaryImage", s(r.getPrimaryImage()));
        m.put("thumbnailImage", s(r.getThumbnailImage()));
        if (r.getGalleryImages() != null && !r.getGalleryImages().isEmpty()) {
            m.put("galleryImages", String.join("|", r.getGalleryImages()));
        }
        m.put("variantImage", s(r.getVariantImage()));
        m.put("category", s(r.getCategory()));
        m.put("brand", s(r.getBrand()));
        m.put("gender", s(r.getGender()));
        m.put("sport", s(r.getSport()));
        m.put("color", s(r.getColor()));
        m.put("size", s(r.getSize()));
        m.put("attributesJson", s(r.getAttributesJson()));
        m.put("variantAttributesJson", s(r.getVariantAttributesJson()));
        return m;
    }

    private static String s(Object v) { return v == null ? "" : String.valueOf(v); }
}
