package com.vicinity24.core.linkedstore.api.automatic.product.load;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadReport;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadRequest;
import com.vicinity24.core.linkedstore.api.automatic.product.load.dto.ProductLoadWrapper;
import com.vicinity24.core.linkedstore.api.automatic.product.load.service.AutomaticProductLoadService;
import com.vicinity24.core.linkedstore.api.automatic.product.load.service.ProductImageResolver;
import com.vicinity24.core.linkedstore.api.automatic.product.load.service.ProductTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AutomaticProductLoadConfiguration {

    @Bean(name = "productUploadJsonMapper")
    public JsonMapper productUploadJsonMapper() {
        JsonMapper mapper = new JsonMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        return mapper;
    }

    @Bean(name = "productUploadCsvMapper")
    public CsvMapper productUploadCsvMapper() {
        CsvMapper mapper = new CsvMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.enable(CsvParser.Feature.SKIP_EMPTY_LINES);
        mapper.enable(CsvParser.Feature.TRIM_SPACES);
        mapper.enable(CsvParser.Feature.IGNORE_TRAILING_UNMAPPABLE);
        return mapper;
    }

    @Bean(name = "productUploadXmlMapper")
    public XmlMapper productUploadXmlMapper() {
        XmlMapper mapper = new XmlMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(com.fasterxml.jackson.dataformat.xml.deser.FromXmlParser.Feature.EMPTY_ELEMENT_AS_NULL, true);
        return mapper;
    }
}
