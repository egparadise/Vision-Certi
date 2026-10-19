package com.inviz.visioncerti.security;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit staff authorization: (OIDC issuer, subject) → (tenant, role). Durable ({@code certi_rt.staff_principals}),
 * never derived from browser input. Managed by tenant administrators through authenticated endpoints, bootstrapped
 * from a protected local file, or initialized exactly once from a fresh, verified Google sign-in for the configured
 * account when no staff principal exists. Only role {@link ActorRole#ADMIN} is accepted: physicians act through the
 * certificate bearer, never through staff tokens.
 */
public class StaffDirectory {

    public enum Status { ACTIVE, DISABLED }

    /** One mapping. {@code id} is also the actor id suffix ({@code staff:<id>}). */
    public record StaffPrincipal(UUID id, String tenantId, String issuer, String subject, String subjectKey,
            ActorRole role, String displayName, Status status, String evidenceRef, Instant createdAt, String createdBy,
            Instant disabledAt, String disabledBy, String disabledReason) {

        public String actorId() {
            return "staff:" + id;
        }
    }

    /** API view (no subject key). */
    public record StaffPrincipalResponse(UUID principalId, String tenantId, String issuer, String subject,
            String role, String displayName, String status, String evidenceRef, Instant createdAt, String createdBy,
            Instant disabledAt, String disabledBy, String disabledReason) {

        static StaffPrincipalResponse of(StaffPrincipal p) {
            return new StaffPrincipalResponse(p.id(), p.tenantId(), p.issuer(), p.subject(), p.role().name(),
                    p.displayName(), p.status().name(), p.evidenceRef(), p.createdAt(), p.createdBy(), p.disabledAt(),
                    p.disabledBy(), p.disabledReason());
        }
    }

    public record CreateStaffRequest(String issuer, String subject, String role, String displayName,
            String evidenceRef) {
    }

    public record DisableStaffRequest(String reason) {
    }

    static final Kind<StaffPrincipal> PRINCIPALS = Kind.of("staff_principals", StaffPrincipal.class)
            .id(StaffPrincipal::id).tenant(StaffPrincipal::tenantId)
            .unique("subject_key", StaffPrincipal::subjectKey,
                    () -> ApiException.conflict("STAFF_ALREADY_MAPPED", "This identity is already mapped"))
            .build();

    static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Logger LOG = LoggerFactory.getLogger(StaffDirectory.class);

    private final Store store;
    private final AuditService audit;
    private final Clock clock;
    private final String issuer;

    public StaffDirectory(Store store, AuditService audit, Clock clock, String issuer) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.issuer = issuer;
    }

    static String subjectKey(String issuer, String subject) {
        return Hashing.sha256Hex(issuer + "\u0000" + subject);
    }

    /** The ACTIVE mapping for a verified identity, if any. */
    public Optional<StaffPrincipal> resolve(String tokenIssuer, String subject) {
        return store.tx(tx -> tx.findOne(PRINCIPALS, "subject_key", subjectKey(tokenIssuer, subject)))
                .filter(p -> p.status() == Status.ACTIVE);
    }

    /** Bind the first administrator only after a fresh, verified Google sign-in for the configured account. */
    public Optional<StaffPrincipal> bootstrapInitialGoogleAdmin(StaffTokenVerifier.VerifiedStaffToken token,
            String expectedEmail, String tenantId) {
        if (expectedEmail == null || expectedEmail.isBlank() || tenantId == null || tenantId.isBlank()
                || !token.isRecentVerifiedGoogleAccount(expectedEmail, clock.instant())) {
            return Optional.empty();
        }
        return store.tx(tx -> {
            tx.lock("staff|initial-google-admin");
            if (tx.countAll(PRINCIPALS) != 0) {
                return Optional.<StaffPrincipal>empty();
            }
            StaffPrincipal principal = newPrincipal(tenantId, token.issuer(), token.subject(), "ADMIN",
                    expectedEmail.strip(), "VERIFIED_GOOGLE_FIRST_ADMIN", "google-first-admin-bootstrap");
            tx.insert(PRINCIPALS, principal);
            audit.record(tenantId, principal.actorId(), "STAFF_PRINCIPAL_BOOTSTRAPPED", "STAFF_PRINCIPAL",
                    principal.id().toString(), AuditService.SUCCESS,
                    Map.of("method", "VERIFIED_GOOGLE_FIRST_ADMIN", "role", "ADMIN"));
            return Optional.of(principal);
        });
    }

    public Optional<StaffPrincipal> find(String tenantId, UUID id) {
        return store.tx(tx -> tx.get(PRINCIPALS, id)).filter(p -> p.tenantId().equals(tenantId));
    }

    public List<StaffPrincipalResponse> list(RequestActor admin) {
        requireAdmin(admin);
        return store.tx(tx -> tx.find(PRINCIPALS, Kind.TENANT, admin.tenantId())).stream()
                .sorted(Comparator.comparing(StaffPrincipal::createdAt))
                .map(StaffPrincipalResponse::of).toList();
    }

    public StaffPrincipalResponse create(RequestActor admin, CreateStaffRequest body) {
        requireAdmin(admin);
        if (body == null) {
            throw invalid("request body is required");
        }
        StaffPrincipal created = store.tx(tx -> {
            StaffPrincipal p = newPrincipal(admin.tenantId(), body.issuer(), body.subject(), body.role(),
                    body.displayName(), body.evidenceRef(), admin.actorId());
            tx.lock("staff|" + p.subjectKey());
            if (tx.findOne(PRINCIPALS, "subject_key", p.subjectKey()).isPresent()) {
                throw ApiException.conflict("STAFF_ALREADY_MAPPED", "This identity is already mapped");
            }
            tx.insert(PRINCIPALS, p);
            audit.record(admin.tenantId(), admin.actorId(), "STAFF_PRINCIPAL_CREATED", "STAFF_PRINCIPAL",
                    p.id().toString(), AuditService.SUCCESS, Map.of("role", p.role().name(),
                            "evidenceRef", p.evidenceRef()));
            return p;
        });
        return StaffPrincipalResponse.of(created);
    }

    public StaffPrincipalResponse disable(RequestActor admin, UUID id, DisableStaffRequest body) {
        requireAdmin(admin);
        String reason = body == null || body.reason() == null ? "" : body.reason().strip();
        if (reason.length() < 2 || reason.length() > 200 || reason.chars().anyMatch(Character::isISOControl)) {
            throw invalid("reason must be 2-200 characters");
        }
        return store.tx(tx -> {
            StaffPrincipal p = tx.lockGet(PRINCIPALS, id).filter(x -> x.tenantId().equals(admin.tenantId()))
                    .orElseThrow(() -> ApiException.notFound("STAFF_PRINCIPAL_NOT_FOUND", "Staff mapping not found"));
            if (admin.actorId().equals(p.actorId())) {
                throw ApiException.conflict("STAFF_SELF_DISABLE", "Administrators cannot disable their own mapping");
            }
            if (p.status() == Status.DISABLED) {
                throw ApiException.conflict("STAFF_ALREADY_DISABLED", "This mapping is already disabled");
            }
            StaffPrincipal disabled = new StaffPrincipal(p.id(), p.tenantId(), p.issuer(), p.subject(),
                    p.subjectKey(), p.role(), p.displayName(), Status.DISABLED, p.evidenceRef(), p.createdAt(),
                    p.createdBy(), clock.instant(), admin.actorId(), reason);
            tx.update(PRINCIPALS, disabled);
            audit.record(admin.tenantId(), admin.actorId(), "STAFF_PRINCIPAL_DISABLED", "STAFF_PRINCIPAL",
                    p.id().toString(), AuditService.SUCCESS, Map.of("reason", reason));
            return StaffPrincipalResponse.of(disabled);
        });
    }

    /**
     * Creates the mappings listed in a protected local JSON file (array of {tenantId, issuer, subject, role,
     * displayName, evidenceRef}) that do not exist yet. Existing mappings - including DISABLED ones - are never
     * changed, so the file cannot re-enable a revoked administrator. Returns the number created.
     */
    public int bootstrap(Path file) {
        JsonNode entries;
        try {
            entries = JsonMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_STAFF_BOOTSTRAP_FILE is not a readable JSON array");
        }
        if (entries == null || !entries.isArray()) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_STAFF_BOOTSTRAP_FILE must be a JSON array");
        }
        int created = 0;
        for (JsonNode entry : entries) {
            StaffPrincipal p;
            try {
                p = newPrincipal(text(entry, "tenantId"), text(entry, "issuer"), text(entry, "subject"),
                        text(entry, "role"), text(entry, "displayName"), text(entry, "evidenceRef"),
                        "bootstrap-file");
            } catch (ApiException e) {
                throw new IllegalStateException("FAIL_CLOSED: invalid entry in VISIONCERTI_STAFF_BOOTSTRAP_FILE: "
                        + e.getMessage());
            }
            boolean inserted = store.tx(tx -> {
                tx.lock("staff|" + p.subjectKey());
                if (tx.findOne(PRINCIPALS, "subject_key", p.subjectKey()).isPresent()) {
                    return false;
                }
                tx.insert(PRINCIPALS, p);
                audit.record(p.tenantId(), "bootstrap-file", "STAFF_PRINCIPAL_BOOTSTRAPPED", "STAFF_PRINCIPAL",
                        p.id().toString(), AuditService.SUCCESS, Map.of("role", p.role().name(),
                                "evidenceRef", p.evidenceRef()));
                return true;
            });
            created += inserted ? 1 : 0;
        }
        LOG.info("staff bootstrap: {} mapping(s) created, {} already present", created, entries.size() - created);
        return created;
    }

    private StaffPrincipal newPrincipal(String tenantId, String tokenIssuer, String subject, String role,
            String displayName, String evidenceRef, String createdBy) {
        if (tenantId == null || !TENANT.matcher(tenantId).matches()) {
            throw invalid("tenantId is invalid");
        }
        if (issuer == null || !issuer.equals(tokenIssuer)) {
            throw invalid("issuer must be the configured staff OIDC issuer");
        }
        if (subject == null || subject.isBlank() || subject.length() > 255
                || subject.chars().anyMatch(Character::isISOControl)) {
            throw invalid("subject must be 1-255 characters");
        }
        if (!"ADMIN".equals(role)) {
            throw invalid("role must be ADMIN (physicians use certificate login, not staff tokens)");
        }
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw invalid("displayName must be 1-100 characters");
        }
        String ref = evidenceRef == null ? "" : evidenceRef.strip();
        if (ref.isEmpty() || ref.length() > 200 || ref.chars().anyMatch(Character::isISOControl)) {
            throw invalid("evidenceRef must be 1-200 characters");
        }
        return new StaffPrincipal(UUID.randomUUID(), tenantId, tokenIssuer, subject,
                subjectKey(tokenIssuer, subject), ActorRole.ADMIN, name, Status.ACTIVE, ref, clock.instant(),
                createdBy, null, null, null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private static void requireAdmin(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_ROLE_REQUIRED", "Administrator role required");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("VALIDATION_FAILED", message);
    }
}
