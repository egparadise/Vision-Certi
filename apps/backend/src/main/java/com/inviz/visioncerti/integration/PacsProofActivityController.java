package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Permanent audit projection; expiring proofs are not the history database. */
@RestController
public final class PacsProofActivityController {
    public record Totals(long requested, long attempted, long verified, long redeemed, long checked, long denied,
            long loginAttempted, long loginSucceeded, long loginFailed, long approvalRequests, long approvalDocuments) { }
    public record Certificate(String fingerprintSha256, String subjectDn, String issuerDn, String notAfter,
            long verified, long redeemed, long checked, Instant lastSeenAt, long attempted, long loginAttempted,
            long loginSucceeded, long loginFailed, long approvalRequests, long approvalDocuments,
            List<String> accounts, List<String> computers) { }
    public record Event(UUID id, Instant occurredAt, String action, String outcome, String correlationId,
            String targetId, Map<String, String> details, boolean legacy) { }
    public record Page(Totals totals, List<Certificate> certificates, List<Event> events,
            int total, int offset, int limit) { }

    private final AuditService audit;
    private final IntegrationClients clients;

    public PacsProofActivityController(AuditService audit, IntegrationClients clients) {
        this.audit = audit;
        this.clients = clients;
    }

    @GetMapping("/v1/admin/pacs-certificate-activity")
    public ResponseEntity<Page> list(RequestActor actor,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        if (actor.role() != ActorRole.ADMIN) throw ApiException.forbidden("ADMIN_REQUIRED", "Administrator access is required");
        if (offset < 0 || limit < 1 || limit > 200 || q.length() > 128) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Invalid activity page or search");
        }
        List<AuditEvent> source = new ArrayList<>(audit.list(actor.tenantId()));
        // Older releases used clientId as the audit tenant. Read the original immutable chain only when
        // the client has exactly one configured tenant. Never expose a multi-tenant legacy chain.
        String legacyTenant = null;
        if (clients.configured()) {
            IntegrationClient client = clients.require();
            if (client.tenantIds().size() == 1 && client.allowsTenant(actor.tenantId())
                    && !client.clientId().equals(actor.tenantId())) {
                legacyTenant = client.clientId();
                source.addAll(audit.list(legacyTenant));
            }
        }
        String search = q.strip().toLowerCase(Locale.ROOT);
        final String oldTenant = legacyTenant;
        List<Event> raw = source.stream().filter(e -> e.action().startsWith("PACS_CERTIFICATE_PROOF_"))
                .sorted(Comparator.comparing(AuditEvent::occurredAt).thenComparingLong(AuditEvent::sequence).reversed())
                .map(e -> {
                    Map<String, String> details = new LinkedHashMap<>(e.details());
                    boolean legacy = e.tenantId().equals(oldTenant);
                    if (legacy) details.putIfAbsent("clientId", oldTenant);
                    return new Event(e.id(), e.occurredAt(), e.action().substring("PACS_CERTIFICATE_PROOF_".length()),
                            e.outcome(), e.correlationId(), e.targetId(), Map.copyOf(details), legacy);
                }).toList();
        // Attribute older challenge attempts via the verified event, without rewriting the immutable log.
        Map<String, String> challengeCertificates = new LinkedHashMap<>();
        for (Event event : raw) {
            if (event.details().containsKey("challengeId") && event.details().containsKey("fingerprintSha256")) {
                challengeCertificates.put(event.details().get("clientId") + ":" + event.details().get("challengeId"), event.details().get("fingerprintSha256"));
            }
        }
        List<Event> events = raw.stream().map(event -> {
            Map<String, String> details = new LinkedHashMap<>(event.details());
            String fingerprint = challengeCertificates.get(details.get("clientId") + ":" + details.get("challengeId"));
            if (fingerprint != null) details.putIfAbsent("fingerprintSha256", fingerprint);
            return new Event(event.id(), event.occurredAt(), event.action(), event.outcome(), event.correlationId(), event.targetId(), Map.copyOf(details), event.legacy());
        }).filter(e -> search.isEmpty() || (e.targetId() + " " + e.action() + " " + e.details().values())
                .toLowerCase(Locale.ROOT).contains(search)).toList();
        Map<String, List<Event>> byCertificate = new LinkedHashMap<>();
        for (Event event : events) {
            String fingerprint = event.details().get("fingerprintSha256");
            if (fingerprint != null) byCertificate.computeIfAbsent(fingerprint, key -> new ArrayList<>()).add(event);
        }
        List<Certificate> certificates = byCertificate.entrySet().stream().map(entry -> {
            List<Event> rows = entry.getValue();
            return new Certificate(entry.getKey(), first(rows, "subjectDn"), first(rows, "issuerDn"),
                    first(rows, "certificateNotAfter"), count(rows, "VERIFIED"), count(rows, "REDEEMED"),
                    count(rows, "CHECKED"), rows.getFirst().occurredAt(), count(rows, "ATTEMPTED"), count(rows, "LOGIN_ATTEMPTED"),
                    count(rows, "LOGIN_SUCCEEDED"), count(rows, "LOGIN_FAILED"), count(rows, "APPROVAL_REQUESTED"), reports(rows),
                    values(rows, "pacsLoginId"), values(rows, "computerName"));
        }).toList();
        Page page = new Page(new Totals(count(events, "REQUESTED"), count(events, "ATTEMPTED"),
                count(events, "VERIFIED"), count(events, "REDEEMED"), count(events, "CHECKED"), count(events, "DENIED"),
                count(events, "LOGIN_ATTEMPTED"), count(events, "LOGIN_SUCCEEDED"), count(events, "LOGIN_FAILED"),
                count(events, "APPROVAL_REQUESTED"), reports(events)),
                certificates, events.stream().skip(offset).limit(limit).toList(), events.size(), offset, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    private static long count(List<Event> events, String action) {
        return events.stream().filter(e -> action.equals(e.action())).count();
    }

    private static String first(List<Event> events, String field) {
        return events.stream().map(e -> e.details().get(field)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static List<String> values(List<Event> events, String field) {
        return events.stream().map(e -> e.details().get(field)).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static long reports(List<Event> events) {
        return events.stream().filter(e -> "APPROVAL_REQUESTED".equals(e.action()) && e.details().containsKey("reportKeySha256"))
                .map(e -> e.details().get("clientId") + ":" + e.details().get("reportKeySha256")).distinct().count();
    }
}
