package com.vicinity24.core.linkedstore.api.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the isolated {@code com.vicinity24.core.linkedstore.api.automatic.product.load}
 * module (parsers, service, controllers, properties classes) for component discovery
 * without modifying the existing application class.
 *
 * <p>This keeps the automatic product-upload feature as a clean sidecar module
 * that can be removed or replaced independently from the rest of the API.</p>
 */
@Configuration
@ComponentScan(basePackages = "com.vicinity24.core.linkedstore.api.automatic.product.load")
public class AutomaticProductLoadModuleConfig {
}
