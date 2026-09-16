package com.vicinity24.core.linkedstore.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventBroadcaster {

    public static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final int REPLAY_BUFFER_MAX = 200;

    private final ObjectMapper objectMapper;

    private final Map<SseEmitter, Predicate<TxEvent>> emitters = new ConcurrentHashMap<>();
    private final List<TxEvent> replayBuffer = new CopyOnWriteArrayList<>();

    // ----- emitter registration ----------------------------------------------------

    /** Register an admin emitter (wildcard — receives all events). */
    public SseEmitter registerAdmin() {
        return register(ev -> true, "admin");
    }

    /** Register a store-scoped emitter. */
    public SseEmitter registerStore(UUID storeId) {
        Objects.requireNonNull(storeId, "storeId required");
        return register(ev ->
            storeId.equals(ev.storeId())
                || storeId.equals(ev.fulfillingStoreId())
                || storeId.equals(ev.originatingStoreId()),
            "store:" + storeId);
    }

    private SseEmitter register(Predicate<TxEvent> filter, String label) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.put(emitter, filter);

        Runnable cleanup = () -> emitters.remove(emitter);
        emitter.onTimeout(cleanup);
        emitter.onCompletion(cleanup);
        emitter.onError(t -> {
            log.debug("TxEventBroadcaster emitter {} error: {}", label, t.toString());
            cleanup.run();
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .comment("SSE open: " + label)
                    .data("{\"ok\":true,\"label\":\"" + label + "\"}"));
        } catch (IOException ignored) {}

        // Replay recent events so newly-opened admin/owner dashboards don't miss last batch of requests
        try {
            for (TxEvent ev : replayBuffer) {
                if (filter.test(ev)) sendSingle(emitter, ev, false);
            }
        } catch (Exception ignore) {}

        log.info("TxEventBroadcaster: registered {} emitter (active={})", label, emitters.size());
        return emitter;
    }

    // ----- broadcast ---------------------------------------------------------------

    public void broadcast(TxEvent event) {
        if (event == null) return;
        final TxEvent stamped = event.eventId() == null
                ? event.withEventId("txev-" + UUID.randomUUID())
                : event;
        addToReplay(stamped);

        final Iterator<Map.Entry<SseEmitter, Predicate<TxEvent>>> it = emitters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SseEmitter, Predicate<TxEvent>> e = it.next();
            final SseEmitter em = e.getKey();
            final Predicate<TxEvent> f = e.getValue();
            if (!f.test(stamped)) continue;
            try {
                sendSingle(em, stamped, true);
            } catch (Exception ex) {
                log.debug("TxEventBroadcaster: removing emitter on send fail: {}", ex.toString());
                em.complete();
                it.remove();
            }
        }
        log.debug("TxEventBroadcaster: broadcast type={} tx={} targetsMatched={}",
                stamped.type(), stamped.transactionId(), emitters.size());
    }

    // ----- helpers -----------------------------------------------------------------

    private void sendSingle(SseEmitter em, TxEvent ev, boolean logOnErr) throws IOException {
        String json;
        try {
            json = objectMapper.writeValueAsString(ev);
        } catch (Exception ex) {
            if (logOnErr) log.warn("TxEventBroadcaster serialize fail", ex);
            json = "{\"type\":\"" + ev.type().name() + "\",\"transactionId\":\"" + ev.transactionId() + "\"}";
        }
        em.send(SseEmitter.event()
                .id(ev.eventId() != null ? ev.eventId() : UUID.randomUUID().toString())
                .name(ev.type().name())
                .reconnectTime(5000L)
                .data(json));
    }

    private void addToReplay(TxEvent ev) {
        replayBuffer.add(ev);
        while (replayBuffer.size() > REPLAY_BUFFER_MAX) replayBuffer.remove(0);
    }

    public List<TxEvent> replayRecent(Predicate<TxEvent> filter, int limit) {
        final int max = Math.min(Math.max(1, limit), REPLAY_BUFFER_MAX);
        final List<TxEvent> out = new ArrayList<>(max);
        for (int i = replayBuffer.size() - 1; i >= 0 && out.size() < max; i--) {
            TxEvent e = replayBuffer.get(i);
            if (filter == null || filter.test(e)) out.add(0, e);
        }
        return out;
    }

    @Scheduled(fixedRate = 30000L)
    public void emitHeartbeats() {
        if (emitters.isEmpty()) return;
        final String heartbeat = "{\"heartbeat\":true,\"serverTime\":\"" + OffsetDateTime.now() + "\",\"activeEmitters\":" + emitters.size() + "}";
        final Iterator<Map.Entry<SseEmitter, Predicate<TxEvent>>> it = emitters.entrySet().iterator();
        while (it.hasNext()) {
            final SseEmitter em = it.next().getKey();
            try {
                em.send(SseEmitter.event().name("heartbeat").data(heartbeat));
            } catch (Exception ignore) {
                em.complete();
                it.remove();
            }
        }
    }

    public int activeEmitterCount() {
        return emitters.size();
    }
}
