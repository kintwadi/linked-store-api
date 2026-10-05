package com.vicinity24.core.linkedstore.api.automatic.product.load.parsers;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class XmlProductParser {

    @Qualifier("productUploadXmlMapper")
    private final XmlMapper xmlMapper;

    public List<ProductLoadRequest> parse(InputStream in) throws Exception {
        ProductLoadWrapper wrapper = xmlMapper.readValue(in, ProductLoadWrapper.class);
        return wrapper == null || wrapper.getProducts() == null
                ? new ArrayList<>()
                : new ArrayList<>(wrapper.getProducts());
    }

    public String writeTemplate(List<ProductLoadRequest> sample) throws Exception {
        ProductLoadWrapper wrapper = ProductLoadWrapper.builder().products(new ArrayList<>(sample)).build();
        String raw = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
        return raw;
    }
}
