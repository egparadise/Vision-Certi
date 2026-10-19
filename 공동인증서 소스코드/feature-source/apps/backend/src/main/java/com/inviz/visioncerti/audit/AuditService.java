package com.inviz.visioncerti.audit;

import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.MemoryStore;
import com.inviz.visioncerti.store.Store;
import com.inviz.visioncerti.store.Tx;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Durable, per-tenant hash-chained audit log.
 *
 * <p>{@link #record} joins the caller's storage transaction; events are chained and inserted right before that
 * transaction commits (after all business writes, tenants in sorted order), so they are durable exactly when the
 * change they describe is, and the per-tenant chain head is the last lock taken. A refused operation that commits
 * (see {@link Store}) keeps its DENIED audit.
 */
@Service
public class AuditService {

    public static final String SUCCESS = "SUCCESS";
    public static final String DENIED = "DENIED";
    private static final String GENESIS = "0".repeat(64);
    private static final String PENDING = "audit.pending";

    /** Chain head per tenant; id is derived from the tenant id. */
    record Head(UUID id, String tenantId, long sequence, String hash) {
    }

    static final Kind<AuditEvent> EVENTS = Kind.of("audit_events", AuditEvent.class)
            .id(AuditEvent::id).tenant(AuditEvent::tenantId)
            .unique("chain_key", e -> chainKey(e.tenantId(), e.sequence()), null)
            .immutable()
            .build();

    static final Kind<Head> HEADS = Kind.of("audit_heads", Head.class)
            .id(Head::id).tenant(Head::tenantId)
            .build();

    private final Store store;
    private final Clock clock;

    @Autowired
    public AuditService(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Stand-alone wiring for unit tests: a private in-memory store. */
    public AuditService(Clock clock) {
        this(new MemoryStore(), clock);
    }

    public Store store() {
        return store;
    }

    /**
     * Records an event in the current transaction (or its own). The returned event carries its id and time; its
     * sequence and hash are assigned when the transaction commits.
     */
    public AuditEvent record(String tenantId, String actorId, String action, String targetType,
            String targetId, String outcome, Map<String, String> details) {
        Instant now = clock.instant();
        String correlationId = MDC.get("correlationId");
        Map<String, String> sortedDetails = details == null ? Map.of() : new TreeMap<>(details);
        AuditEvent pending = new AuditEvent(UUID.randomUUID(), tenantId, 0, now, actorId, action, targetType,
                targetId, outcome, correlationId, Map.copyOf(sortedDetails), null, null);
        store.run(tx -> {
            List<AuditEvent> queue = tx.attachment(PENDING, () -> {
                List<AuditEvent> list = new ArrayList<>();
                tx.beforeCommit(() -> flush(tx, list));
                return list;
            });
            queue.add(pending);
        });
        return pending;
    }

    private static void flush(Tx tx, List<AuditEvent> queue) {
        Map<String, List<AuditEvent>> byTenant = new TreeMap<>();
        for (AuditEvent e : queue) {
            byTenant.computeIfAbsent(e.tenantId(), t -> new ArrayList<>()).add(e);
        }
        byTenant.forEach((tenantId, events) -> {
            UUID headId = headId(tenantId);
            tx.lock("audit-head|" + tenantId);
            Head head = tx.lockGet(HEADS, headId).orElse(null);
            long sequence = head == null ? 0 : head.sequence();
            String previousHash = head == null ? GENESIS : head.hash();
            for (AuditEvent e : events) {
                sequence++;
                String hash = Hashing.sha256Hex(String.join("|", previousHash, tenantId, Long.toString(sequence),
                        e.occurredAt().toString(), e.actorId(), e.action(), e.targetType(), String.valueOf(e.targetId()),
                        e.outcome(), new TreeMap<>(e.details()).toString()));
                tx.insert(EVENTS, new AuditEvent(e.id(), tenantId, sequence, e.occurredAt(), e.actorId(), e.action(),
                        e.targetType(), e.targetId(), e.outcome(), e.correlationId(), e.details(), previousHash, hash));
                previousHash = hash;
            }
            Head next = new Head(headId, tenantId, sequence, previousHash);
            if (head == null) {
                tx.insert(HEADS, next);
            } else {
                tx.update(HEADS, next);
            }
        });
        queue.clear();
    }

    /** Newest first. */
    public List<AuditEvent> list(String tenantId) {
        return store.tx(tx -> tx.find(EVENTS, Kind.TENANT, tenantId)).reversed();
    }

    public int count(String tenantId) {
        return store.tx(tx -> tx.count(EVENTS, Kind.TENANT, tenantId, null)).intValue();
    }

    static UUID headId(String tenantId) {
        return UUID.nameUUIDFromBytes(("audit-head|" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    static String chainKey(String tenantId, long sequence) {
        return Hashing.sha256Hex(tenantId) + "|" + String.format("%019d", sequence);
    }
}
