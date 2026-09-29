package com.vicinity24.core.linkedstore.api.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the isolated {@code com.vicinity24.core.linkedstore.subscription}
 * module (controllers, services, exception handlers, properties classes) for
 * component/discovery, without modifying the existing application class.
 *
 * <p>This keeps the subscription module as a clean, sidecar sibling package
 * that can be removed or replaced independently of the {@code api.*} core.</p>
 */
@Configuration
@ComponentScan(basePackages = "com.vicinity24.core.linkedstore.subscription")
public class SubscriptionModuleConfig {
}
