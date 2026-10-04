package com.vicinity24.core.linkedstore.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "linkedstore.brand")
public class BrandProperties {

    private String displayName = "Linked-Store";
    private String contactSalesTo = "info@vicinity24.com";
}
