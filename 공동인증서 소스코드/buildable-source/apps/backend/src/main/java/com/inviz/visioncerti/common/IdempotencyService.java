package com.inviz.visioncerti.common;

import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable {@code Idempotency-Key} handling for mutations, shared by all instances.
 *
 * <p>Scope is tenant + actor + operation + key; only its SHA-256 is stored. The first request claims the scope
 * (IN_PROGRESS with a {@link #LEASE} lease) in its own transaction, runs the action, then stores the successful
 * response (status, selected headers, JSON body). A repeated key with the same request fingerprint replays the stored
 * response; with a different fingerprint it is rejected with 409 {@code IDEMPOTENCY_KEY_REUSED}; while the first
 * request is still running it gets 409 {@code IDEMPOTENCY_REQUEST_IN_PROGRESS} (retryable). Failed executions are not
 * stored, so a client may retry them. A claim whose holder crashed can be taken over after the lease.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    static final Duration LEASE = Duration.ofMinutes(2);
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final List<String> REPLAYED_HEADERS = List.of(HttpHeaders.CACHE_CONTROL, HttpHeaders.LOCATION);

    record IdempotencyRecord(UUID id, String tenantId, String scopeHash, String fingerprint, boolean completed,
            UUID holder, Instant leaseUntil, int status, Map<String, String> headers, String body, Instant createdAt) {
    }

    static final Kind<IdempotencyRecord> RECORDS = Kind.of("idempotency_records", IdempotencyRecord.class)
            .id(IdempotencyRecord::id).tenant(IdempotencyRecord::tenantId)
            .unique("scope_hash", IdempotencyRecord::scopeHash, IdempotencyService::inProgress)
            .build();

    private final Store store;
    private final Clock clock;
    private final ObjectMapper json;

    public IdempotencyService(Store store, Clock clock, ObjectMapper json) {
        this.store = store;
        this.clock = clock;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    public <T> ResponseEntity<T> execute(RequestActor actor, String operation, String key,
            String fingerprint, Supplier<ResponseEntity<T>> action) {
        if (key == null || key.isBlank()) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required for this operation");
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8-128 characters of [A-Za-z0-9._:-]");
        }
        String scopeHash = Hashing.sha256Hex(String.join("\u0000", actor.tenantId(), actor.actorId(), operation, key));
        UUID holder = UUID.randomUUID();
        IdempotencyRecord stored = store.tx(tx -> {
            Instant now = clock.instant();
            IdempotencyRecord existing = tx.lockFindOne(RECORDS, "scope_hash", scopeHash).orElse(null);
            if (existing == null) {
                tx.insert(RECORDS, new IdempotencyRecord(UUID.randomUUID(), actor.tenantId(), scopeHash, fingerprint,
                        false, holder, now.plus(LEASE), 0, Map.of(), null, now));
                return null;
            }
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request payload");
            }
            if (existing.completed()) {
                return existing;
            }
            if (now.isBefore(existing.leaseUntil())) {
                throw inProgress();
            }
            // The previous holder did not finish within its lease (crash or restart): take over.
            tx.update(RECORDS, new IdempotencyRecord(existing.id(), existing.tenantId(), scopeHash, fingerprint, false,
                    holder, now.plus(LEASE), 0, Map.of(), null, existing.createdAt()));
            return null;
        });
        if (stored != null) {
            return (ResponseEntity<T>) replay(stored);
        }
        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            release(scopeHash, holder);
            throw e;
        }
        complete(scopeHash, holder, response);
        return response;
    }

    private void complete(String scopeHash, UUID holder, ResponseEntity<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : REPLAYED_HEADERS) {
            String value = response.getHeaders().getFirst(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        String body = response.getBody() == null ? null : json.writeValueAsString(response.getBody());
        store.run(tx -> tx.lockFindOne(RECORDS, "scope_hash", scopeHash)
                .filter(r -> holder.equals(r.holder()) && !r.completed())
                .ifPresent(r -> tx.update(RECORDS, new IdempotencyRecord(r.id(), r.tenantId(), scopeHash,
                        r.fingerprint(), true, holder, r.leaseUntil(), response.getStatusCode().value(), headers, body,
                        r.createdAt()))));
    }

    private void release(String scopeHash, UUID holder) {
        store.run(tx -> tx.lockFindOne(RECORDS, "scope_hash", scopeHash)
                .filter(r -> holder.equals(r.holder()) && !r.completed())
                .ifPresent(r -> tx.delete(RECORDS, r.id())));
    }

    private ResponseEntity<JsonNode> replay(IdempotencyRecord record) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(record.status());
        record.headers().forEach(builder::header);
        JsonNode body = record.body() == null ? null : json.readTree(record.body());
        return builder.body(body);
    }

    private static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed; retry shortly", true);
    }
}
