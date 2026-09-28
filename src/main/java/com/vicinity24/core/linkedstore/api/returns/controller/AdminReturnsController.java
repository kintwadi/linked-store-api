package com.vicinity24.core.linkedstore.api.returns.controller;

import com.vicinity24.core.linkedstore.api.returns.dto.InspectionActionRequest;
import com.vicinity24.core.linkedstore.api.returns.dto.InspectionCountsResponse;
import com.vicinity24.core.linkedstore.api.returns.dto.InspectionSummaryResponse;
import com.vicinity24.core.linkedstore.api.returns.dto.PaginatedInspectionListResponse;
import com.vicinity24.core.linkedstore.api.returns.service.ReturnedInspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminReturnsController {

    private final ReturnedInspectionService returnedInspectionService;

    @GetMapping("/returns")
    public ResponseEntity<PaginatedInspectionListResponse> listReturns(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            Authentication auth) {

        if (size > 100) {
            size = 100;
        }
        if (size < 1) {
            size = 1;
        }
        if (page < 0) {
            page = 0;
        }

        Pageable pageable = PageRequest.of(page, size);

        PaginatedInspectionListResponse response = returnedInspectionService.listForCaller(auth, pageable, status, search);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/returns/counts")
    public ResponseEntity<InspectionCountsResponse> getReturnsCounts(
            Authentication auth) {

        Pageable pageable = PageRequest.of(0, 1);
        PaginatedInspectionListResponse response = returnedInspectionService.listForCaller(auth, pageable, null, null);
        return ResponseEntity.ok(response.counts());
    }

    @GetMapping("/returns/{id}")
    public ResponseEntity<InspectionSummaryResponse> getReturnById(
            @PathVariable String id,
            Authentication auth) {

        UUID recordUuid;
        try {
            recordUuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid return inspection record ID format: " + id);
        }

        InspectionSummaryResponse response = returnedInspectionService.findById(recordUuid, auth);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/returns/{id}/approve")
    public ResponseEntity<InspectionSummaryResponse> approveReturn(
            @PathVariable String id,
            @RequestBody(required = false) InspectionActionRequest body,
            Authentication auth) {

        UUID recordUuid;
        try {
            recordUuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid return inspection record ID format: " + id);
        }

        String notes = (body != null) ? body.notes() : null;

        InspectionSummaryResponse response = returnedInspectionService.approve(recordUuid, notes, auth);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/returns/{id}/reject")
    public ResponseEntity<InspectionSummaryResponse> rejectReturn(
            @PathVariable String id,
            @RequestBody(required = false) InspectionActionRequest body,
            Authentication auth) {

        UUID recordUuid;
        try {
            recordUuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid return inspection record ID format: " + id);
        }

        String reason;
        if (body == null || body.notes() == null || body.notes().isBlank()) {
            reason = "No reason provided";
        } else {
            reason = body.notes();
        }

        InspectionSummaryResponse response = returnedInspectionService.reject(recordUuid, reason, auth);
        return ResponseEntity.ok(response);
    }
}
