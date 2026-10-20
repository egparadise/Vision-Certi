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
            Instant retainUntil, boolean used) { }
    record Proof(UUID id, String clientId, String codeHash, String fingerprint, String subjectDn, String issuerDn,
            Instant certificateNotAfter, String certificateDer, List<String> chainDer, Instant verifiedAt,
            Instant codeExpiresAt, Instant expiresAt, Instant redeemedAt) { }

    static final Kind<Challenge> CHALLENGES = Kind.of("pacs_proof_challenges", Challenge.class)
            .id(Challenge::id).expiresAt(Challenge::retainUntil).column("client_id", Challenge::clientId).build();
    static final Kind<Proof> PROOFS = Kind.of("pacs_certificate_proofs", Proof.class)
            .id(Proof::id).expiresAt(Proof::expiresAt)
            .unique("code_hash", Proof::codeHash, null).column("client_id", Proof::clientId).build();

    public record StartRequest(String audience) { }
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
        IntegrationClient client = clients.require();
        if (request == null || !client.clientId().equals(request.audience())) {
            throw ApiException.unprocessable("PROOF_AUDIENCE_INVALID", "PACS audience is invalid");
        }
        if (!validator.trustConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICATE_TRUST_NOT_CONFIGURED",
                    "Certificate trust is not configured");
        }
        doctors.countLoginChallenge(source);
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
                    expiresAt.plus(CHALLENGE_TTL), false));
        });
        return new ChallengeResponse(id, null, PURPOSE, null, null, serviceId, nonce, now, expiresAt,
                payload, Base64.getEncoder().encodeToString(bytes), CertificateValidator.ALLOWED_ALGORITHMS);
    }

    public ProofCodeResponse verify(LoginRequest request) {
        if (request == null || request.challengeId() == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "Certificate proof is required");
        }
        UUID challengeId;
        try { challengeId = UUID.fromString(request.challengeId()); }
        catch (IllegalArgumentException e) { throw ApiException.notFound("CHALLENGE_NOT_FOUND", "Challenge not found"); }
        Instant now = clock.instant();
        Challenge challenge = store.tx(tx -> {
            Challenge found = tx.lockGet(CHALLENGES, challengeId).orElse(null);
            if (found == null || !now.isBefore(found.retainUntil())) {
                throw ApiException.notFound("CHALLENGE_NOT_FOUND", "Challenge not found");
            }
            if (found.used()) throw ApiException.conflict("CHALLENGE_ALREADY_USED", "Challenge already used");
            tx.update(CHALLENGES, new Challenge(found.id(), found.clientId(), found.payload(), found.issuedAt(),
                    found.expiresAt(), found.retainUntil(), true));
            if (!now.isBefore(found.expiresAt())) {
                throw new ApiException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "Challenge expired");
            }
            return found;
        });
        audit.record(challenge.clientId(), "certificate", "PACS_CERTIFICATE_PROOF_ATTEMPTED", "CHALLENGE",
                challenge.id().toString(), AuditService.SUCCESS, Map.of());
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
            audit.record(challenge.clientId(), "certificate", "PACS_CERTIFICATE_PROOF_DENIED", "CHALLENGE",
                    challenge.id().toString(), AuditService.DENIED, Map.of("code", failure.code()));
            throw failure;
        }
        String code = randomBase64();
        Instant verifiedAt = clock.instant();
        Proof proof = new Proof(UUID.randomUUID(), challenge.clientId(), Hashing.sha256Hex(code), fingerprint,
                leaf.getSubjectX500Principal().getName(), leaf.getIssuerX500Principal().getName(),
                leaf.getNotAfter().toInstant(), request.certificate(), List.copyOf(request.chain() == null
                        ? List.of() : request.chain()), verifiedAt, verifiedAt.plus(CODE_TTL),
                verifiedAt.plus(PROOF_TTL), null);
        store.run(tx -> {
            tx.deleteExpired(PROOFS, verifiedAt);
            tx.insert(PROOFS, proof);
            audit.record(challenge.clientId(), "certificate", "PACS_CERTIFICATE_PROOF_VERIFIED", "CERTIFICATE",
                    proof.id().toString(), AuditService.SUCCESS, Map.of("fingerprintSha256", fingerprint));
        });
        return new ProofCodeResponse(code, challenge.clientId(), proof.codeExpiresAt());
    }

    public Receipt redeem(IntegrationClient client, RedeemRequest request) {
        String code = request == null ? null : request.verificationCode();
        if (code == null || !CODE.matcher(code).matches()) throw invalidCode();
        Instant now = clock.instant();
        Proof proof = store.tx(tx -> {
            Proof found = tx.lockFindOne(PROOFS, "code_hash", Hashing.sha256Hex(code)).orElse(null);
            if (found == null || found.redeemedAt() != null || !found.clientId().equals(client.clientId())
                    || !now.isBefore(found.codeExpiresAt())) throw invalidCode();
            tx.update(PROOFS, new Proof(found.id(), found.clientId(), found.codeHash(), found.fingerprint(),
                    found.subjectDn(), found.issuerDn(), found.certificateNotAfter(), found.certificateDer(),
                    found.chainDer(), found.verifiedAt(), found.codeExpiresAt(), found.expiresAt(), now));
            return found;
        });
        return status(client, proof.id());
    }

    public Receipt status(IntegrationClient client, UUID verificationId) {
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
