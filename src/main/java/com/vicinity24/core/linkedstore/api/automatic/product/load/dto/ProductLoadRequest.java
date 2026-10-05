package com.vicinity24.core.linkedstore.api.automatic.product.load.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized intermediate representation of a single product row (CSV) or element
 * (JSON/XML) coming off the wire. This is the contract users edit in their
 * templates; all three formats (JSON / CSV / XML) are converted to this shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "product")
public class ProductLoadRequest {

    @JsonProperty("title")
    @JsonAlias({"name", "product_title", "productName", "label"})
    private String title;

    @JsonProperty("description")
    @JsonAlias({"desc", "product_description", "shortDescription", "body"})
    private String description;

    @JsonProperty("sku")
    @JsonAlias({"variantSku", "variant_sku", "productSku"})
    private String sku;

    @JsonProperty("retailPrice")
    @JsonAlias({"retail_price", "price", "retail", "retailPriceCents"})
    private String retailPrice;

    @JsonProperty("wholesalePrice")
    @JsonAlias({"wholesale_price", "cost", "wholesale", "wholesalePriceCents"})
    private String wholesalePrice;

    @JsonProperty("stockQuantity")
    @JsonAlias({"stock", "qty", "quantity", "inventory", "stock_quantity"})
    private String stockQuantity;

    @JsonProperty("status")
    @JsonAlias({"productStatus", "product_status", "variantStatus", "variant_status"})
    private String status;

    @JsonProperty("primaryImage")
    @JsonAlias({
            "image", "primary_image", "cover", "coverImage", "mainImage",
            "primary_image_url", "primaryImageUrl", "productImage"
    })
    private String primaryImage;

    @JsonProperty("thumbnailImage")
    @JsonAlias({"thumbnail", "thumb", "thumbnail_image", "thumbnail_url", "thumbnailUrl"})
    private String thumbnailImage;

    @JsonProperty("galleryImages")
    @JsonAlias({"gallery", "images", "gallery_image_urls", "galleryImageUrls"})
    @JacksonXmlElementWrapper(localName = "galleryImages")
    @JacksonXmlProperty(localName = "image")
    @Builder.Default
    private List<String> galleryImages = new ArrayList<>();

    @JsonProperty("variantImage")
    @JsonAlias({"variant_image", "variantImageUrl", "variant_image_url", "variant_image_file"})
    private String variantImage;

    @JsonProperty("category")
    @JsonAlias({"product_category", "type", "productType", "product_type"})
    private String category;

    @JsonProperty("brand")
    @JsonAlias({"product_brand", "manufacturer"})
    private String brand;

    @JsonProperty("gender")
    @JsonAlias({"product_gender"})
    private String gender;

    @JsonProperty("sport")
    private String sport;

    @JsonProperty("color")
    @JsonAlias({"product_color"})
    private String color;

    @JsonProperty("size")
    @JsonAlias({"variant_size", "variantSize"})
    private String size;

    @JsonProperty("attributesJson")
    @JsonAlias({"attributes", "attributes_json", "metadata"})
    private String attributesJson;

    @JsonProperty("variantAttributesJson")
    @JsonAlias({"variantAttributes", "variant_attributes", "variant_metadata"})
    private String variantAttributesJson;

    /** For CSV / simple uploads: accept an arbitrary map to capture any extra
     *  CSV columns into variantAttributes JSON so nothing is lost. */
    @Builder.Default
    private transient Map<String, Object> extraColumns = new HashMap<>();
}
