package com.vicinity24.core.linkedstore.api.controller;

import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.entity.Store;
import com.vicinity24.core.linkedstore.api.repository.StoreRepository;
import com.vicinity24.core.linkedstore.api.security.AuthenticationFacade;
import com.vicinity24.core.linkedstore.api.security.CurrentUser;
import com.vicinity24.core.linkedstore.api.service.TransactionEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class TransactionEventController {

    private final TransactionEventBroadcaster broadcaster;
    private final AuthenticationFacade auth;
    private final StoreRepository storeRepository;

    /** Global admin SSE stream — every event, every store. */
    @GetMapping(value = "/api/admin/sse/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter adminStream() {
        auth.requireGlobalAdmin();
        return broadcaster.registerAdmin();
    }

    /** Store-owner / clerk-scoped SSE stream for a single store. */
    @GetMapping(value = "/api/stores/{storeId}/sse/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter storeStream(@PathVariable("storeId") String storeIdStr) {
        UUID storeId;
        try { storeId = UUID.fromString(storeIdStr); }
        catch (IllegalArgumentException e) { throw new SecurityException("Invalid store id"); }

        Store store = storeRepository.findById(storeId).orElseThrow(() -> new SecurityException("Store not found"));
        final CurrentUser cu = auth.current();
        if (!cu.isGlobalAdmin() && (cu.getStoreId() == null || !cu.getStoreId().equals(store.getId()))) {
            throw new SecurityException("You do not belong to this store");
        }
        return broadcaster.registerStore(storeId);
    }

    /** REST replay endpoint — admin recent N events (useful to render bell dropdown before EventSource is open). */
    @GetMapping("/api/admin/sse/events/recent")
    public ResponseEntity<List<TxEvent>> adminRecent(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        auth.requireGlobalAdmin();
        return ResponseEntity.ok(broadcaster.replayRecent(null, limit));
    }

    /** Store-scoped REST replay. */
    @GetMapping("/api/stores/{storeId}/sse/events/recent")
    public ResponseEntity<List<TxEvent>> storeRecent(
            @PathVariable("storeId") String storeIdStr,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        UUID storeId;
        try { storeId = UUID.fromString(storeIdStr); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().build(); }
        Store store = storeRepository.findById(storeId).orElse(null);
        if (store == null) return ResponseEntity.notFound().build();
        final CurrentUser cu = auth.current();
        if (!cu.isGlobalAdmin() && (cu.getStoreId() == null || !cu.getStoreId().equals(store.getId()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(broadcaster.replayRecent(ev ->
                storeId.equals(ev.storeId())
                        || storeId.equals(ev.fulfillingStoreId())
                        || storeId.equals(ev.originatingStoreId()), limit));
    }
}
