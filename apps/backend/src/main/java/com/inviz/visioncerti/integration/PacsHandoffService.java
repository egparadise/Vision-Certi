package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.DoctorPrincipal;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.DoctorService.IntegrationIdentity;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.HandoffResponse;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.VerifiedIdentityResponse;
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
    private static final int MAX_PENDING = 10_000;

    /** A pending handoff; only the SHA-256 of the code is stored. Consumed on the first redeem attempt. */
    record Handoff(UUID id, String codeHash, String clientId, String tenantId, IntegrationIdentity identity,
            Instant createdAt, Instant expiresAt, boolean consumed) {
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

    public HandoffResponse issue(DoctorPrincipal principal, String audience) {
        IntegrationClient client = clients.require();
        if (audience == null || !audience.equals(client.clientId())) {
            throw ApiException.unprocessable("HANDOFF_AUDIENCE_INVALID", "audience must be the configured PACS client");
        }
        if (!client.allowsTenant(principal.tenantId())) {
            throw ApiException.forbidden("INTEGRATION_TENANT_FORBIDDEN", "이 기관은 PACS 연동 대상이 아닙니다.");
        }
        IntegrationIdentity identity = doctors.requireFreshCertificateSession(principal, maxLoginAge);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        store.run(tx -> {
            tx.deleteExpired(HANDOFFS, now);
            if (tx.count(HANDOFFS, "client_id", client.clientId(), now) >= MAX_PENDING) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HANDOFF_CAPACITY", "잠시 후 다시 시도해 주세요.", true);
            }
            tx.insert(HANDOFFS, new Handoff(UUID.randomUUID(), Hashing.sha256Hex(code), client.clientId(),
                    identity.tenantId(), identity, now, expiresAt, false));
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
                        found.identity(), found.createdAt(), found.expiresAt(), true));
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
                doctors.registeredLicenseNumber(current.doctorId()).orElse(null));
    }

    private static ApiException handoffInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "HANDOFF_INVALID", "Handoff code is invalid or expired");
    }
}
