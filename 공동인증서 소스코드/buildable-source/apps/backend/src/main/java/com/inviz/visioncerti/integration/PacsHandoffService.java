package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.admin.SettingKey;
import com.inviz.visioncerti.admin.SystemSettings;
import org.springframework.beans.factory.annotation.Autowired;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.DoctorPrincipal;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.DoctorService.IntegrationIdentity;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.HandoffResponse;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.VerifiedIdentityResponse;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.CertificateLoginConfirmationRequest;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.CertificateLoginConfirmationResponse;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * One-time handoff codes that let the PACS server learn, server to server, which physician completed a fresh
 * certificate login at Vision-Certi. Codes are 32 random bytes, stored only as SHA-256, valid for a short TTL,
 * bound to one integration client (audience) and consumed on the first redeem attempt. Handoffs are durable rows
 * (shared by all instances, surviving restarts until they expire); no storage transaction is held while calling
 * {@link DoctorService}.
 */
public class PacsHandoffService {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODE_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PACS_ID = Pattern.compile("[A-Za-z0-9_.@:-]{1,128}");
    private static final Pattern PACS_SESSION_ID = Pattern.compile("[0-9]{1,20}");
    private static final int MAX_PENDING = 10_000;

    /** A pending handoff; only the SHA-256 of the code is stored. Consumed on the first redeem attempt. */
    record Handoff(UUID id, String codeHash, String clientId, String tenantId, IntegrationIdentity identity,
            Instant createdAt, Instant expiresAt, boolean consumed, Instant confirmedAt,
            String pacsAccountId, String pacsLoginId, String pacsSessionId) {
    }

    static final Kind<Handoff> HANDOFFS = Kind.of("pacs_handoffs", Handoff.class)
            .id(Handoff::id).tenant(Handoff::tenantId).expiresAt(Handoff::expiresAt)
            .unique("code_hash", Handoff::codeHash, null)
            .column("client_id", Handoff::clientId)
            .build();

    private final IntegrationClients clients;
    private final DoctorService doctors;
    private final AuditService audit;
    private final Store store;
    private final Clock clock;
    private final Duration ttl;
    private final Duration maxLoginAge;
    private final SecureRandom random = new SecureRandom();

    public PacsHandoffService(IntegrationClients clients, DoctorService doctors, AuditService audit, Clock clock,
            Duration ttl, Duration maxLoginAge) {
        this.clients = clients;
        this.doctors = doctors;
        this.audit = audit;
        this.store = audit.store();
        this.clock = clock;
        this.ttl = ttl;
        this.maxLoginAge = maxLoginAge;
    }

    private volatile SystemSettings settings;

    /** Administrator overrides ({@link SystemSettings}); absent in unit tests that construct the service directly. */
    @Autowired(required = false)
    public void useSettings(SystemSettings settings) {
        this.settings = settings;
    }

    private Duration ttl() {
        SystemSettings s = settings;
        return s == null ? ttl : s.duration(SettingKey.PACS_HANDOFF_TTL);
    }

    private Duration maxLoginAge() {
        SystemSettings s = settings;
        return s == null ? maxLoginAge : s.duration(SettingKey.PACS_MAX_CERTIFICATE_LOGIN_AGE);
    }

    public HandoffResponse issue(DoctorPrincipal principal, String audience) {
        IntegrationClient client = clients.require();
        if (audience == null || !audience.equals(client.clientId())) {
            throw ApiException.unprocessable("HANDOFF_AUDIENCE_INVALID", "audience must be the configured PACS client");
        }
        if (!client.allowsTenant(principal.tenantId())) {
            throw ApiException.forbidden("INTEGRATION_TENANT_FORBIDDEN", "이 기관은 PACS 연동 대상이 아닙니다.");
        }
        IntegrationIdentity identity = doctors.requireFreshCertificateSession(principal, maxLoginAge());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl());
        store.run(tx -> {
            tx.deleteExpired(HANDOFFS, now);
            if (tx.count(HANDOFFS, "client_id", client.clientId(), now) >= MAX_PENDING) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HANDOFF_CAPACITY", "잠시 후 다시 시도해 주세요.", true);
            }
            tx.insert(HANDOFFS, new Handoff(UUID.randomUUID(), Hashing.sha256Hex(code), client.clientId(),
                    identity.tenantId(), identity, now, expiresAt, false, null, null, null, null));
            audit.record(identity.tenantId(), identity.doctorId().toString(), "PACS_HANDOFF_ISSUED", "DOCTOR",
                    identity.doctorId().toString(), AuditService.SUCCESS, Map.of("audience", client.clientId(),
                            "certificateEnrollmentId", identity.certificateEnrollmentId().toString()));
        });
        return new HandoffResponse(code, expiresAt, client.clientId(), identity.tenantId());
    }

    public VerifiedIdentityResponse redeem(IntegrationClient client, String code) {
        Handoff handoff = null;
        if (code != null && CODE.matcher(code).matches()) {
            String codeHash = Hashing.sha256Hex(code);
            // Consumed on any attempt, durably and across instances: the row lock makes the first redeem win.
            handoff = store.tx(tx -> {
                Handoff found = tx.lockFindOne(HANDOFFS, "code_hash", codeHash).orElse(null);
                if (found == null || found.consumed()) {
                    return null;
                }
                tx.update(HANDOFFS, new Handoff(found.id(), found.codeHash(), found.clientId(), found.tenantId(),
                        found.identity(), found.createdAt(), found.expiresAt(), true, found.confirmedAt(),
                        found.pacsAccountId(), found.pacsLoginId(), found.pacsSessionId()));
                return found;
            });
        }
        Instant now = clock.instant();
        if (handoff == null || !now.isBefore(handoff.expiresAt()) || !handoff.clientId().equals(client.clientId())
                || !client.allowsTenant(handoff.identity().tenantId())) {
            throw handoffInvalid();
        }
        IntegrationIdentity original = handoff.identity();
        IntegrationIdentity current;
        try {
            current = doctors.confirmIntegrationSession(original.doctorId(), original.sessionId(),
                    original.certificateEnrollmentId());
        } catch (ApiException e) {
            audit.record(original.tenantId(), client.actorId(), "PACS_HANDOFF_REDEEM_DENIED", "DOCTOR",
                    original.doctorId().toString(), AuditService.DENIED, Map.of("code", e.code()));
            throw ApiException.forbidden("HANDOFF_IDENTITY_NOT_VALID",
                    "The physician's certificate session or enrollment is no longer valid");
        }
        audit.record(current.tenantId(), client.actorId(), "PACS_HANDOFF_REDEEMED", "DOCTOR",
                current.doctorId().toString(), AuditService.SUCCESS,
                Map.of("certificateEnrollmentId", current.certificateEnrollmentId().toString()));
        return new VerifiedIdentityResponse(current.tenantId(), current.doctorId(), "ACTIVE", current.identityMode(),
                current.certificateEnrollmentId(), current.certificateFingerprint(), current.authenticatedAt(),
                current.sessionExpiresAt(), now, true,
                doctors.registeredLicenseNumber(current.doctorId()).orElse(null),
                "PC_CERTIFICATE".equals(current.identityMode()));
    }

    /** Correlates the PACS account actually logged in with the already redeemed certificate proof. */
    public CertificateLoginConfirmationResponse confirmLogin(IntegrationClient client,
            CertificateLoginConfirmationRequest body) {
        if (body == null || body.handoffCodeSha256() == null || !CODE_HASH.matcher(body.handoffCodeSha256()).matches()
                || body.tenantId() == null || body.doctorId() == null || body.certificateEnrollmentId() == null
                || body.pacsAccountId() == null || !PACS_ID.matcher(body.pacsAccountId()).matches()
                || body.pacsLoginId() == null || !PACS_ID.matcher(body.pacsLoginId()).matches()
                || body.pacsSessionId() == null || !PACS_SESSION_ID.matcher(body.pacsSessionId()).matches()) {
            throw ApiException.unprocessable("LOGIN_CONFIRMATION_INVALID", "PACS certificate login confirmation is invalid");
        }
        Instant now = clock.instant();
        return store.tx(tx -> {
            Handoff handoff = tx.lockFindOne(HANDOFFS, "code_hash", body.handoffCodeSha256()).orElse(null);
            if (handoff == null || !handoff.consumed() || !handoff.clientId().equals(client.clientId())
                    || !client.allowsTenant(handoff.tenantId()) || !handoff.tenantId().equals(body.tenantId())
                    || !handoff.identity().doctorId().equals(body.doctorId())
                    || !handoff.identity().certificateEnrollmentId().equals(body.certificateEnrollmentId())
                    || !now.isBefore(handoff.createdAt().plus(Duration.ofMinutes(5)))) {
                throw ApiException.forbidden("LOGIN_CONFIRMATION_NOT_AUTHORIZED",
                        "No matching redeemed certificate handoff exists for this PACS login");
            }
            if (handoff.confirmedAt() != null) {
                if (!body.pacsAccountId().equals(handoff.pacsAccountId())
                        || !body.pacsLoginId().equals(handoff.pacsLoginId())
                        || !body.pacsSessionId().equals(handoff.pacsSessionId())) {
                    throw ApiException.conflict("LOGIN_CONFIRMATION_CONFLICT", "PACS login was already confirmed for another account");
                }
                return new CertificateLoginConfirmationResponse(true, handoff.confirmedAt(),
                        doctors.confirmIntegrationSession(body.doctorId(), handoff.identity().sessionId(),
                                body.certificateEnrollmentId()).sessionExpiresAt());
            }
            tx.update(HANDOFFS, new Handoff(handoff.id(), handoff.codeHash(), handoff.clientId(), handoff.tenantId(),
                    handoff.identity(), handoff.createdAt(), handoff.expiresAt(), true, now,
                    body.pacsAccountId(), body.pacsLoginId(), body.pacsSessionId()));
            audit.record(handoff.tenantId(), client.actorId(), "PACS_CERTIFICATE_LOGIN_COMPLETED", "DOCTOR",
                    body.doctorId().toString(), AuditService.SUCCESS,
                    Map.of("pacsAccountId", body.pacsAccountId(), "pacsLoginId", body.pacsLoginId(),
                            "pacsSessionId", body.pacsSessionId(),
                            "visionSessionId", handoff.identity().sessionId().toString(),
                            "certificateEnrollmentId", body.certificateEnrollmentId().toString(),
                            "certificateFingerprintSha256", handoff.identity().certificateFingerprint(),
                            "expiresAt", handoff.identity().sessionExpiresAt().toString(),
                            "clientId", client.clientId()));
            return new CertificateLoginConfirmationResponse(true, now, handoff.identity().sessionExpiresAt());
        });
    }

    public PacsIntegrationDtos.PacsSessionStatus status(IntegrationClient client, String pacsSessionId) {
        var login = findConfirmedLogin(client, pacsSessionId);
        var identity = doctors.confirmIntegrationSession(UUID.fromString(login.targetId()),
                UUID.fromString(login.details().get("visionSessionId")),
                UUID.fromString(login.details().get("certificateEnrollmentId")));
        int extended = (int) audit.list(identity.tenantId()).stream()
                .filter(e -> "PACS_CERTIFICATE_SESSION_EXTENDED".equals(e.action())
                        && pacsSessionId.equals(e.details().get("pacsSessionId")))
                .count();
        return new PacsIntegrationDtos.PacsSessionStatus(true, identity.tenantId(), identity.doctorId(),
                pacsSessionId, identity.certificateFingerprint(), identity.sessionExpiresAt(), extended);
    }

    public PacsIntegrationDtos.PacsSessionStatus extend(IntegrationClient client, String pacsSessionId,
            PacsIntegrationDtos.PacsSessionExtendRequest body) {
        if (body == null || body.extendMinutes() == null || body.extendMinutes() != 60
                || !pacsSessionId.equals(body.pacsSessionId())) {
            throw ApiException.unprocessable("PACS_SESSION_EXTEND_INVALID", "Extension must be exactly 60 minutes");
        }
        var login = findConfirmedLogin(client, pacsSessionId);
        if (!login.tenantId().equals(body.tenantId()) || !login.targetId().equals(String.valueOf(body.doctorId()))
                || !body.pacsAccountId().equals(login.details().get("pacsAccountId"))
                || !Hashing.constantTimeEquals(body.certificateFingerprintSha256(),
                        login.details().get("certificateFingerprintSha256"))) {
            throw ApiException.forbidden("PACS_SESSION_MISMATCH", "PACS session is not bound to this certificate");
        }
        var extended = doctors.extendIntegrationSession(body.doctorId(),
                UUID.fromString(login.details().get("visionSessionId")),
                UUID.fromString(login.details().get("certificateEnrollmentId")));
        audit.record(extended.tenantId(), client.actorId(), "PACS_CERTIFICATE_SESSION_EXTENDED", "DOCTOR",
                extended.doctorId().toString(), AuditService.SUCCESS,
                Map.of("pacsSessionId", pacsSessionId, "certificateFingerprintSha256",
                        extended.certificateFingerprint(), "expiresAt", extended.sessionExpiresAt().toString()));
        return status(client, pacsSessionId);
    }

    private com.inviz.visioncerti.audit.AuditEvent findConfirmedLogin(IntegrationClient client, String pacsSessionId) {
        if (pacsSessionId == null || !PACS_SESSION_ID.matcher(pacsSessionId).matches()) {
            throw ApiException.notFound("PACS_SESSION_NOT_FOUND", "PACS certificate session not found");
        }
        for (String tenantId : client.tenantIds()) {
            var event = audit.list(tenantId).stream()
                    .filter(e -> "PACS_CERTIFICATE_LOGIN_COMPLETED".equals(e.action())
                            && client.clientId().equals(e.details().get("clientId"))
                            && pacsSessionId.equals(e.details().get("pacsSessionId")))
                    .findFirst();
            if (event.isPresent()) return event.get();
        }
        throw ApiException.notFound("PACS_SESSION_NOT_FOUND", "PACS certificate session not found");
    }

    /** Match a report request to a confirmed PACS login, then re-check the live INVIZ certificate session. */
    public void confirmReportCertificate(IntegrationClient client, String tenantId, UUID doctorId,
            String pacsSessionId, String certificateFingerprint) {
        if (pacsSessionId == null || !PACS_SESSION_ID.matcher(pacsSessionId).matches()
                || certificateFingerprint == null || !CODE_HASH.matcher(certificateFingerprint).matches()) {
            throw ApiException.unprocessable("REPORT_CERTIFICATE_PROOF_REQUIRED",
                    "PACS session and certificate SHA-256 fingerprint are required");
        }
        var login = audit.list(tenantId).stream()
                .filter(e -> "PACS_CERTIFICATE_LOGIN_COMPLETED".equals(e.action())
                        && doctorId.toString().equals(e.targetId())
                        && client.clientId().equals(e.details().get("clientId"))
                        && pacsSessionId.equals(e.details().get("pacsSessionId")))
                .findFirst().orElse(null);
        if (login == null || !Hashing.constantTimeEquals(certificateFingerprint,
                login.details().get("certificateFingerprintSha256"))) {
            audit.record(tenantId, client.actorId(), "PACS_REPORT_CERTIFICATE_CHECK", "DOCTOR",
                    doctorId.toString(), AuditService.DENIED, Map.of("pacsSessionId", pacsSessionId,
                            "reason", "LOGIN_OR_FINGERPRINT_MISMATCH"));
            throw ApiException.forbidden("REPORT_CERTIFICATE_MISMATCH", "Certificate does not match the PACS login");
        }
        try {
            var current = doctors.confirmIntegrationSession(doctorId,
                    UUID.fromString(login.details().get("visionSessionId")),
                    UUID.fromString(login.details().get("certificateEnrollmentId")));
            doctors.requireCurrentIntegrationCertificate(current);
            if (!tenantId.equals(current.tenantId()) || !Hashing.constantTimeEquals(certificateFingerprint,
                    current.certificateFingerprint())) {
                throw ApiException.forbidden("REPORT_CERTIFICATE_MISMATCH", "Certificate session changed");
            }
        } catch (RuntimeException e) {
            audit.record(tenantId, client.actorId(), "PACS_REPORT_CERTIFICATE_CHECK", "DOCTOR",
                    doctorId.toString(), AuditService.DENIED, Map.of("pacsSessionId", pacsSessionId,
                            "reason", "SESSION_NOT_ACTIVE"));
            throw ApiException.forbidden("REPORT_CERTIFICATE_SESSION_INVALID", "Certificate session is no longer active");
        }
        audit.record(tenantId, client.actorId(), "PACS_REPORT_CERTIFICATE_CHECK", "DOCTOR",
                doctorId.toString(), AuditService.SUCCESS, Map.of("pacsSessionId", pacsSessionId,
                        "certificateFingerprintSha256", certificateFingerprint));
    }

    private static ApiException handoffInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "HANDOFF_INVALID", "Handoff code is invalid or expired");
    }
}
