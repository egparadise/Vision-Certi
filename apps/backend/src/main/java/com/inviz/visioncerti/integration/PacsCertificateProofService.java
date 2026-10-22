package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.LoginRequest;
import com.inviz.visioncerti.certificate.CertificateValidator;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * PACS certificate possession attestation. It deliberately does not know or check a PACS ID/password, and it does
 * not create a Vision-Certi physician account. The PACS authenticates its own account and binds the returned
 * fingerprint to that account. A local JSON file is never an authorization source: PACS redeems the one-time code
 * and checks the live proof through its HMAC-authenticated server route before login/approval.
 */
public final class PacsCertificateProofService {
    private static final String PURPOSE = "PACS_CERTIFICATE_PROOF";
    private static final String PAYLOAD_HEADER = "vision-certi-certificate-auth-v1";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final Duration PROOF_TTL = Duration.ofHours(2);
    private static final int MAX_LIVE = 10_000;

    record Challenge(UUID id, String clientId, byte[] payload, Instant issuedAt, Instant expiresAt,
            Instant retainUntil, boolean used, String tenantId) { }
    record Proof(UUID id, String clientId, String codeHash, String fingerprint, String subjectDn, String issuerDn,
            Instant certificateNotAfter, String certificateDer, List<String> chainDer, Instant verifiedAt,
            Instant codeExpiresAt, Instant expiresAt, Instant redeemedAt, String tenantId) { }

    static final Kind<Challenge> CHALLENGES = Kind.of("pacs_proof_challenges", Challenge.class)
            .id(Challenge::id).expiresAt(Challenge::retainUntil).column("client_id", Challenge::clientId).build();
    static final Kind<Proof> PROOFS = Kind.of("pacs_certificate_proofs", Proof.class)
            .id(Proof::id).expiresAt(Proof::expiresAt)
            .unique("code_hash", Proof::codeHash, null).column("client_id", Proof::clientId).build();

    public record StartRequest(String audience, String tenantId) {
        public StartRequest(String audience) { this(audience, null); }
    }
    public record ProofCodeResponse(String verificationCode, String audience, Instant expiresAt) {
        @Override public String toString() { return "ProofCodeResponse[verificationCode=<redacted>]"; }
    }
    public record RedeemRequest(String verificationCode) {
        @Override public String toString() { return "RedeemRequest[verificationCode=<redacted>]"; }
    }
    public record Receipt(boolean verified, String authentication, UUID verificationId, String audience,
            String certificateFingerprintSha256, String subjectDn, String issuerDn,
            Instant certificateNotAfter, Instant verifiedAt, Instant expiresAt) { }

    private final IntegrationClients clients;
    private final CertificateValidator validator;
    private final DoctorService doctors;
    private final AuditService audit;
    private final Store store;
    private final Clock clock;
    private final String serviceId;
    private final SecureRandom random = new SecureRandom();

    public PacsCertificateProofService(IntegrationClients clients, CertificateValidator validator,
            DoctorService doctors, AuditService audit, Clock clock, String serviceId) {
        this.clients = clients;
        this.validator = validator;
        this.doctors = doctors;
        this.audit = audit;
        this.store = audit.store();
        this.clock = clock;
        this.serviceId = serviceId;
    }

    public ChallengeResponse start(StartRequest request, String source) {
        return start(request, new PacsRequestContext(source, "", "", ""));
    }

    public ChallengeResponse start(StartRequest request, PacsRequestContext context) {
        try {
            return startChecked(request, context);
        } catch (ApiException failure) {
            recordUnboundDenial(context, "CHALLENGE", failure.code());
            throw failure;
        }
    }

    private ChallengeResponse startChecked(StartRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        if (request == null || !client.clientId().equals(request.audience())) {
            throw ApiException.unprocessable("PROOF_AUDIENCE_INVALID", "PACS audience is invalid");
        }
        if (!validator.trustConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICATE_TRUST_NOT_CONFIGURED",
                    "Certificate trust is not configured");
        }
        String tenant = tenant(client, request.tenantId());
        doctors.countLoginChallenge(context.peerIp());
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String nonce = randomBase64();
        Instant expiresAt = now.plus(CHALLENGE_TTL);
        String payload = String.join("\n", PAYLOAD_HEADER, "purpose=" + PURPOSE,
                "challengeId=" + id, "serviceId=" + serviceId, "audience=" + client.clientId(),
                "nonce=" + nonce, "issuedAt=" + now, "expiresAt=" + expiresAt);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        store.run(tx -> {
            tx.deleteExpired(CHALLENGES, now);
            if (tx.countLive(CHALLENGES, now) >= MAX_LIVE) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CERTIFICATE_CAPACITY_EXCEEDED",
                        "Too many certificate challenges", true);
            }
            tx.insert(CHALLENGES, new Challenge(id, client.clientId(), bytes, now, expiresAt,
                    expiresAt.plus(CHALLENGE_TTL), false, tenant));
            record(tenant, client.clientId(), "REQUESTED", id.toString(), AuditService.SUCCESS, context,
                    Map.of("challengeId", id.toString(), "challengeExpiresAt", expiresAt.toString()));
        });
        return new ChallengeResponse(id, null, PURPOSE, null, null, serviceId, nonce, now, expiresAt,
                payload, Base64.getEncoder().encodeToString(bytes), CertificateValidator.ALLOWED_ALGORITHMS);
    }

    public ProofCodeResponse verify(LoginRequest request) {
        return verify(request, PacsRequestContext.EMPTY);
    }

    public ProofCodeResponse verify(LoginRequest request, PacsRequestContext context) {
        if (request == null || request.challengeId() == null) {
            recordUnboundDenial(context, "VERIFY", "VALIDATION_FAILED");
            throw ApiException.unprocessable("VALIDATION_FAILED", "Certificate proof is required");
        }
        UUID challengeId;
        try { challengeId = UUID.fromString(request.challengeId()); }
        catch (IllegalArgumentException e) {
            recordUnboundDenial(context, "VERIFY", "CHALLENGE_NOT_FOUND");
            throw ApiException.notFound("CHALLENGE_NOT_FOUND", "Challenge not found");
        }
        Instant now = clock.instant();
        Challenge challenge = store.tx(tx -> {
            Challenge found = tx.lockGet(CHALLENGES, challengeId).orElse(null);
            if (found == null || !now.isBefore(found.retainUntil())) {
                recordUnboundDenial(context, "VERIFY", "CHALLENGE_NOT_FOUND");
                throw ApiException.notFound("CHALLENGE_NOT_FOUND", "Challenge not found");
            }
            if (found.used()) {
                record(tenant(clients.require(), found.tenantId()), found.clientId(), "DENIED", found.id().toString(),
                        AuditService.DENIED, context, Map.of("code", "CHALLENGE_ALREADY_USED", "stage", "VERIFY"));
                throw ApiException.conflict("CHALLENGE_ALREADY_USED", "Challenge already used");
            }
            tx.update(CHALLENGES, new Challenge(found.id(), found.clientId(), found.payload(), found.issuedAt(),
                    found.expiresAt(), found.retainUntil(), true, found.tenantId()));
            if (!now.isBefore(found.expiresAt())) {
                record(tenant(clients.require(), found.tenantId()), found.clientId(), "DENIED", found.id().toString(),
                        AuditService.DENIED, context, Map.of("code", "CHALLENGE_EXPIRED", "stage", "VERIFY"));
                throw new ApiException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "Challenge expired");
            }
            return found;
        });
        String tenant = tenant(clients.require(), challenge.tenantId());
        record(tenant, challenge.clientId(), "ATTEMPTED", challenge.id().toString(), AuditService.SUCCESS, context,
                Map.of("challengeId", challenge.id().toString()));
        X509Certificate leaf;
        String fingerprint;
        try {
            leaf = validator.parse(request.certificate());
            fingerprint = CertificateValidator.fingerprint(leaf);
            if (!fingerprint.equals(request.fingerprintSha256())) {
                throw ApiException.unprocessable("FINGERPRINT_MISMATCH", "Certificate fingerprint does not match");
            }
            validator.checkLeafPolicy(leaf);
            validator.verifySignature(leaf, request.signatureAlgorithm(), challenge.payload(), request.signature());
            List<X509Certificate> chain = validator.parseChain(request.chain());
            CertificateValidator.Validation validation = validator.validate(leaf, chain);
            CertificateValidator.requireGood(validation.revocation());
        } catch (ApiException failure) {
            record(tenant, challenge.clientId(), "DENIED", challenge.id().toString(), AuditService.DENIED, context,
                    Map.of("code", failure.code(), "challengeId", challenge.id().toString(), "stage", "VERIFY"));
            throw failure;
        }
        String code = randomBase64();
        Instant verifiedAt = clock.instant();
        Proof proof = new Proof(UUID.randomUUID(), challenge.clientId(), Hashing.sha256Hex(code), fingerprint,
                leaf.getSubjectX500Principal().getName(), leaf.getIssuerX500Principal().getName(),
                leaf.getNotAfter().toInstant(), request.certificate(), List.copyOf(request.chain() == null
                        ? List.of() : request.chain()), verifiedAt, verifiedAt.plus(CODE_TTL),
                verifiedAt.plus(PROOF_TTL), null, tenant);
        Map<String, String> evidence = evidence(proof);
        evidence.put("challengeId", challenge.id().toString());
        evidence.put("certificateNotBefore", leaf.getNotBefore().toInstant().toString());
        evidence.put("certificateSerialNumber", leaf.getSerialNumber().toString(16));
        evidence.put("signatureAlgorithm", request.signatureAlgorithm());
        evidence.put("validation", "SIGNATURE_PKIX_REVOCATION");
        store.run(tx -> {
            tx.deleteExpired(PROOFS, verifiedAt);
            tx.insert(PROOFS, proof);
            record(tenant, challenge.clientId(), "VERIFIED", proof.id().toString(), AuditService.SUCCESS, context, evidence);
        });
        return new ProofCodeResponse(code, challenge.clientId(), proof.codeExpiresAt());
    }

    public Receipt redeem(IntegrationClient client, RedeemRequest request) {
        return redeem(client, request, PacsRequestContext.EMPTY);
    }

    public Receipt redeem(IntegrationClient client, RedeemRequest request, PacsRequestContext context) {
        try {
            return redeemChecked(client, request, context);
        } catch (ApiException failure) {
            // No client-supplied code (or its hash) enters the permanent audit trail.
            if (client.tenantIds().size() == 1) record(tenant(client, null), client.clientId(), "DENIED", "redeem",
                    AuditService.DENIED, context, Map.of("code", failure.code(), "stage", "REDEEM"));
            throw failure;
        }
    }

    private Receipt redeemChecked(IntegrationClient client, RedeemRequest request, PacsRequestContext context) {
        String code = request == null ? null : request.verificationCode();
        if (code == null || !CODE.matcher(code).matches()) throw invalidCode();
        Instant now = clock.instant();
        Proof proof = store.tx(tx -> {
            Proof found = tx.lockFindOne(PROOFS, "code_hash", Hashing.sha256Hex(code)).orElse(null);
            if (found == null || found.redeemedAt() != null || !found.clientId().equals(client.clientId())
                    || !now.isBefore(found.codeExpiresAt())) throw invalidCode();
            tx.update(PROOFS, new Proof(found.id(), found.clientId(), found.codeHash(), found.fingerprint(),
                    found.subjectDn(), found.issuerDn(), found.certificateNotAfter(), found.certificateDer(),
                    found.chainDer(), found.verifiedAt(), found.codeExpiresAt(), found.expiresAt(), now, found.tenantId()));
            return found;
        });
        Receipt receipt = statusChecked(client, proof.id());
        record(tenant(client, proof.tenantId()), client.clientId(), "REDEEMED", proof.id().toString(),
                AuditService.SUCCESS, context, evidence(proof));
        return receipt;
    }

    public Receipt status(IntegrationClient client, UUID verificationId) {
        return status(client, verificationId, PacsRequestContext.EMPTY);
    }

    public Receipt status(IntegrationClient client, UUID verificationId, PacsRequestContext context) {
        Proof proof = store.tx(tx -> tx.get(PROOFS, verificationId).orElse(null));
        // Never log another client's certificate evidence in this client's tenant.
        if (proof != null && !proof.clientId().equals(client.clientId())) proof = null;
        String scope = proof == null ? (client.tenantIds().size() == 1 ? tenant(client, null) : null)
                : tenant(client, proof.tenantId());
        Map<String, String> evidence = proof == null ? new HashMap<>() : evidence(proof);
        evidence.put("verificationId", verificationId.toString());
        evidence.put("stage", "STATUS");
        try {
            Receipt receipt = statusChecked(client, verificationId);
            record(scope, client.clientId(), "CHECKED", verificationId.toString(), AuditService.SUCCESS, context, evidence);
            return receipt;
        } catch (ApiException failure) {
            evidence.put("code", failure.code());
            if (scope != null) record(scope, client.clientId(), "DENIED", verificationId.toString(),
                    AuditService.DENIED, context, evidence);
            throw failure;
        }
    }

    private Receipt statusChecked(IntegrationClient client, UUID verificationId) {
        Proof proof = store.tx(tx -> tx.get(PROOFS, verificationId).orElse(null));
        if (proof == null || proof.redeemedAt() == null || !proof.clientId().equals(client.clientId())
                || !clock.instant().isBefore(proof.expiresAt())) {
            throw ApiException.forbidden("CERTIFICATE_PROOF_INACTIVE", "Certificate proof is missing or expired");
        }
        X509Certificate leaf = validator.parse(proof.certificateDer());
        if (!proof.fingerprint().equals(CertificateValidator.fingerprint(leaf))) {
            throw ApiException.forbidden("CERTIFICATE_PROOF_INACTIVE", "Certificate proof changed");
        }
        validator.checkLeafPolicy(leaf);
        CertificateValidator.requireGood(validator.validate(leaf, validator.parseChain(proof.chainDer())).revocation());
        return receipt(proof);
    }

    private static Receipt receipt(Proof proof) {
        return new Receipt(true, "Y", proof.id(), proof.clientId(), proof.fingerprint(), proof.subjectDn(),
                proof.issuerDn(), proof.certificateNotAfter(), proof.verifiedAt(), proof.expiresAt());
    }

    private static String tenant(IntegrationClient client, String requested) {
        if (requested != null && client.allowsTenant(requested)) return requested;
        if (requested == null && client.tenantIds().size() == 1) return client.tenantIds().iterator().next();
        throw ApiException.unprocessable("PROOF_TENANT_REQUIRED", "An allowed PACS tenant is required");
    }

    private void record(String tenant, String client, String action, String target, String outcome,
            PacsRequestContext context, Map<String, String> values) {
        Map<String, String> details = new HashMap<>(context.details());
        details.putAll(values);
        details.put("clientId", client);
        details.put("purpose", PURPOSE);
        audit.record(tenant, "integration:" + client, "PACS_CERTIFICATE_PROOF_" + action,
                "PACS_CERTIFICATE_PROOF", target, outcome, details);
    }

    private void recordUnboundDenial(PacsRequestContext context, String stage, String code) {
        if (clients.configured() && clients.require().tenantIds().size() == 1) {
            IntegrationClient client = clients.require();
            record(tenant(client, null), client.clientId(), "DENIED", "request", AuditService.DENIED,
                    context, Map.of("stage", stage, "code", code));
        }
    }

    private static Map<String, String> evidence(Proof proof) {
        Map<String, String> details = new HashMap<>();
        details.put("verificationId", proof.id().toString());
        details.put("fingerprintSha256", proof.fingerprint());
        details.put("subjectDn", proof.subjectDn());
        details.put("issuerDn", proof.issuerDn());
        details.put("certificateNotAfter", proof.certificateNotAfter().toString());
        details.put("verifiedAt", proof.verifiedAt().toString());
        details.put("expiresAt", proof.expiresAt().toString());
        return details;
    }

    private String randomBase64() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CERTIFICATE_PROOF_CODE_INVALID",
                "Certificate proof code is invalid or expired");
    }
}
