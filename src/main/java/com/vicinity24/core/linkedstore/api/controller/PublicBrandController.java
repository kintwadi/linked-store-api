package com.vicinity24.core.linkedstore.api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public/brand")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PublicBrandController {

    @Value("${spring.application.name:DinRetail}")
    private String applicationName;

    @GetMapping("")
    public Map<String, String> getBrand() {
        return Map.of(
                "displayName", applicationName
        );
    }
}
