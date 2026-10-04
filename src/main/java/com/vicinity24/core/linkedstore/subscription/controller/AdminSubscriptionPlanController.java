package com.vicinity24.core.linkedstore.subscription.controller;

import com.vicinity24.core.linkedstore.subscription.dto.AdminPlanDto;
import com.vicinity24.core.linkedstore.subscription.dto.UpsertPlanRequest;
import com.vicinity24.core.linkedstore.subscription.service.AdminSubscriptionPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/admin/subscription-plans")
@RequiredArgsConstructor
@CrossOrigin(value = "*", maxAge = 3600)
public class AdminSubscriptionPlanController {

    private final AdminSubscriptionPlanService adminService;

    @GetMapping
    public ResponseEntity<List<AdminPlanDto>> listPlans() {
        return ResponseEntity.ok(adminService.listEditablePlans());
    }

    @PutMapping("/{planCode}")
    public ResponseEntity<AdminPlanDto> upsertPlan(
            @PathVariable String planCode,
            @RequestBody UpsertPlanRequest request) {
        return ResponseEntity.ok(adminService.upsert(planCode, request));
    }
}
