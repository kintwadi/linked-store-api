package com.vicinity24.core.linkedstore.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vicinity24.core.linkedstore.api.dto.TxEvent;
import com.vicinity24.core.linkedstore.api.dto.TxEventType;
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
    /** Terminal events (EXPIRED/CANCELED/UNAVAILABLE) older than this many minutes are omitted from replay. */
    private static final int TERMINAL_EVENT_REPLAY_MINUTES = 10;
    /** Success events (PAID/PICKED_UP) older than this many minutes are omitted from replay. */
    private static final int SUCCESS_EVENT_REPLAY_MINUTES = 5;
    /**
     * During replay (emitter registration or /recent endpoint), show AT MOST one event per
     * transactionId — the newest, most progressed one. So a transaction that went
     * RESERVED → READY → PAID is replayed once as PAID, not as a wall of 3 stale prior events.
     */
    private static final boolean REPLAY_ONLY_NEWEST_EVENT_PER_TRANSACTION = true;

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

        // Replay recent events so newly-opened admin/owner dashboards don't miss last batch of requests.
        // Stale terminal events (EXPIRED / CANCELED / UNAVAILABLE older than N minutes) are skipped so
        // new logins do not see a long list of already-dead notifications.
        try {
            final OffsetDateTime now = OffsetDateTime.now();
            List<TxEvent> candidates = new ArrayList<>();
            for (TxEvent ev : replayBuffer) {
                if (!filter.test(ev)) continue;
                if (isEventOmittedFromReplay(ev, now)) continue;
                candidates.add(ev);
            }
            List<TxEvent> toReplay = REPLAY_ONLY_NEWEST_EVENT_PER_TRANSACTION
                    ? newestPerTransaction(candidates)
                    : candidates;
            for (TxEvent ev : toReplay) sendSingle(emitter, ev, false);
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
        // Remove any earlier events for the same transactionId so the replay buffer contains
        // at most one (latest) event per transaction. This keeps the buffer small and means
        // RESERVED is dropped as soon as READY arrives, READY dropped when PAID arrives.
        if (ev.transactionId() != null) {
            replayBuffer.removeIf(x -> ev.transactionId().equals(x.transactionId()));
        }
        replayBuffer.add(ev);
        while (replayBuffer.size() > REPLAY_BUFFER_MAX) replayBuffer.remove(0);
    }

    public List<TxEvent> replayRecent(Predicate<TxEvent> filter, int limit) {
        final int max = Math.min(Math.max(1, limit), REPLAY_BUFFER_MAX);
        final List<TxEvent> out = new ArrayList<>(max);
        final OffsetDateTime now = OffsetDateTime.now();
        // Iterate newest-first
        for (int i = replayBuffer.size() - 1; i >= 0 && out.size() < max; i--) {
            TxEvent e = replayBuffer.get(i);
            if (filter != null && !filter.test(e)) continue;
            if (isEventOmittedFromReplay(e, now)) continue;
            out.add(0, e);
        }
        if (REPLAY_ONLY_NEWEST_EVENT_PER_TRANSACTION) {
            List<TxEvent> deduped = newestPerTransaction(out);
            while (deduped.size() > max) deduped.remove(0);
            return deduped;
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

    /** Periodically prune stale events from the replay buffer so fresh dashboard loads stay clean. */
    @Scheduled(fixedRate = 60000L)
    public void pruneStaleTerminalFromReplayBuffer() {
        final OffsetDateTime now = OffsetDateTime.now();
        final int before = replayBuffer.size();
        replayBuffer.removeIf(ev -> isEventOmittedFromReplay(ev, now));
        // Also enforce newest-only-per-transactionId here (belt and suspenders).
        dedupReplayBufferNewestPerTransactionId();
        final int after = replayBuffer.size();
        if (before != after) {
            log.debug("TxEventBroadcaster: replay buffer pruned stale events: {} -> {}", before, after);
        }
    }

    /** Unconditionally deduplicate so replay buffer only keeps the newest event per transactionId. */
    private void dedupReplayBufferNewestPerTransactionId() {
        if (replayBuffer.isEmpty()) return;
        final Map<UUID, TxEvent> newestByTx = new LinkedHashMap<>();
        for (TxEvent ev : replayBuffer) {
            UUID tx = ev.transactionId();
            if (tx == null) continue;
            TxEvent existing = newestByTx.get(tx);
            if (existing == null || isNewerOrSame(ev, existing)) {
                newestByTx.put(tx, ev);
            }
        }
        final Set<String> keepIds = new HashSet<>();
        for (TxEvent ev : newestByTx.values()) {
            if (ev.eventId() != null) keepIds.add(ev.eventId());
        }
        replayBuffer.removeIf(ev -> {
            UUID tx = ev.transactionId();
            if (tx == null) return false; // keep events without txId untouched
            return ev.eventId() != null && !keepIds.contains(ev.eventId());
        });
    }

    private static boolean isNewerOrSame(TxEvent a, TxEvent b) {
        OffsetDateTime ta = a.createdAt();
        OffsetDateTime tb = b.createdAt();
        if (ta != null && tb != null) return !ta.isBefore(tb);
        if (ta != null) return true;
        return false;
    }

    /** Given a list of events, return at most one per transactionId, picking the newest one. */
    private static List<TxEvent> newestPerTransaction(Collection<TxEvent> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        final Map<UUID, TxEvent> map = new LinkedHashMap<>();
        final List<TxEvent> txLess = new ArrayList<>();
        for (TxEvent ev : events) {
            UUID tx = ev.transactionId();
            if (tx == null) {
                txLess.add(ev);
                continue;
            }
            TxEvent existing = map.get(tx);
            if (existing == null || isNewerOrSame(ev, existing)) {
                map.put(tx, ev);
            }
        }
        List<TxEvent> out = new ArrayList<>(map.size() + txLess.size());
        out.addAll(map.values());
        out.addAll(txLess);
        out.sort(Comparator.comparing(e -> {
            OffsetDateTime t = e.createdAt();
            return t != null ? t.toInstant() : java.time.Instant.EPOCH;
        }));
        return out;
    }

    /** Returns true if the event should be omitted from replay endpoints/new-emitter replays. */
    private boolean isEventOmittedFromReplay(TxEvent ev, OffsetDateTime now) {
        if (ev == null) return true;
        final TxEventType t = ev.type();
        final OffsetDateTime createdAt = ev.createdAt();

        // A. Terminal failures: short 10min grace then drop entirely.
        if (t == TxEventType.EXPIRED || t == TxEventType.CANCELLED || t == TxEventType.UNAVAILABLE) {
            if (createdAt == null) return false;
            return createdAt.plusMinutes(TERMINAL_EVENT_REPLAY_MINUTES).isBefore(now);
        }

        // B. Success events (PAID/PICKED_UP). Once a customer has paid and taken custody, the
        //    notification has no remaining value for store clerks. Hide after a short grace
        //    window so clerks still see a green confirmation toast briefly.
        if (t == TxEventType.PAID || t == TxEventType.PICKED_UP) {
            if (createdAt == null) return false;
            return createdAt.plusMinutes(SUCCESS_EVENT_REPLAY_MINUTES).isBefore(now);
        }
        return false;
    }

    @Deprecated
    private boolean isStaleTerminal(TxEvent ev, OffsetDateTime now) {
        return isEventOmittedFromReplay(ev, now);
    }

    public int activeEmitterCount() {
        return emitters.size();
    }
}
