package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.config.BrandProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public/brand")
@CrossOrigin(origins = "*", maxAge = 3600)
@RequiredArgsConstructor
public class PublicBrandController {

    private final BrandProperties brandProperties;

    @GetMapping("")
    public Map<String, String> getBrand() {
        return Map.of(
                "displayName", brandProperties.getDisplayName()
        );
    }
}
