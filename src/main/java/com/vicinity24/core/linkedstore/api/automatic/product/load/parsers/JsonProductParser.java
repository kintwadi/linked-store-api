package com.vicinity24.core.linkedstore.api.automatic.product.load.parsers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonProductParser {

    @Qualifier("productUploadJsonMapper")
    private final JsonMapper jsonMapper;

    public List<ProductLoadRequest> parse(InputStream in) throws Exception {
        JsonNode root = jsonMapper.readTree(in);
        if (root == null || root.isNull()) {
            return Collections.emptyList();
        }
        if (root.isArray()) {
            return jsonMapper.convertValue(root, new TypeReference<List<ProductLoadRequest>>() {});
        }
        if (root.has("products")) {
            ProductLoadWrapper wrapper = jsonMapper.convertValue(root, ProductLoadWrapper.class);
            return new ArrayList<>(wrapper.getProducts());
        }
        if (root.isObject()) {
            ProductLoadRequest single = jsonMapper.convertValue(root, ProductLoadRequest.class);
            if (single.getTitle() == null && single.getSku() == null) {
                // Treat unknown single-object wrapper as a potential "data/products" envelope
                if (root.has("data") && root.get("data").isArray()) {
                    return jsonMapper.convertValue(root.get("data"), new TypeReference<List<ProductLoadRequest>>() {});
                }
            }
            return List.of(single);
        }
        return Collections.emptyList();
    }

    public String writeTemplate(List<ProductLoadRequest> sample) throws Exception {
        ProductLoadWrapper wrapper = ProductLoadWrapper.builder().products(new ArrayList<>(sample)).build();
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
    }
}
