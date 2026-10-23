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
import java.security.MessageDigest;
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
 * and checks the live proof through HMAC server routes or per-attempt S256 PC routes before login/approval.
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
            Instant retainUntil, boolean used, String tenantId, String pcNonce, String codeChallenge) { }
    record Proof(UUID id, String clientId, String codeHash, String fingerprint, String subjectDn, String issuerDn,
            Instant certificateNotAfter, String certificateDer, List<String> chainDer, Instant verifiedAt,
            Instant codeExpiresAt, Instant expiresAt, Instant redeemedAt, String tenantId,
            String pcNonce, String codeChallenge, AccountLease lease) { }

    record AccountLease(String accountKeyHash, ClientInfo owner, String bootId, Instant lastSeenAt, boolean ended) { }
    public static final int ACCOUNT_LEASE_HOURS = 72;
    private static final Duration ACCOUNT_TTL = Duration.ofHours(ACCOUNT_LEASE_HOURS);
    private static final Duration OWNER_TIMEOUT = Duration.ofSeconds(90);
    public record AccountActivateRequest(String audience, UUID verificationId, String codeVerifier,
            String accountKey, String bootId, ClientInfo clientInfo) {
        @Override public String toString() { return "AccountActivateRequest[<redacted>]"; }
    }
    public record AccountStatusRequest(String audience, String accountKey, ClientInfo clientInfo) {
        @Override public String toString() { return "AccountStatusRequest[<redacted>]"; }
    }
    public record AccountHeartbeatRequest(String audience, UUID verificationId, String codeVerifier,
            String bootId, String deviceId, boolean close) {
        @Override public String toString() { return "AccountHeartbeatRequest[<redacted>]"; }
    }
    public record AccountEventRequest(AccountStatusRequest account, ClientEventRequest event) {
        @Override public String toString() { return "AccountEventRequest[<redacted>]"; }
    }
    public record AccountStatus(boolean active, String reason, Instant serverTime, Instant verifiedAt,
            Instant expiresAt, Instant sourceOnlineUntil, String originComputerName, Receipt receipt) { }

    static final Kind<Challenge> CHALLENGES = Kind.of("pacs_proof_challenges", Challenge.class)
            .id(Challenge::id).expiresAt(Challenge::retainUntil).column("client_id", Challenge::clientId).build();
    static final Kind<Proof> PROOFS = Kind.of("pacs_certificate_proofs", Proof.class)
            .id(Proof::id).expiresAt(Proof::expiresAt)
            .unique("code_hash", Proof::codeHash, null).column("client_id", Proof::clientId).build();

    public record StartRequest(String audience, String tenantId, String pcNonce, String codeChallenge, String codeChallengeMethod) {
        public StartRequest(String audience) { this(audience, null, null, null, null); }
        public StartRequest(String audience, String tenantId) { this(audience, tenantId, null, null, null); }
    }
    public record PcRedeemRequest(String audience, String verificationCode, String codeVerifier, String nonce, ClientInfo clientInfo) {
        public PcRedeemRequest(String audience, String verificationCode, String codeVerifier, String nonce) {
            this(audience, verificationCode, codeVerifier, nonce, null);
        }
        @Override public String toString() { return "PcRedeemRequest[<redacted>]"; }
    }
    public record PcStatusRequest(String audience, UUID verificationId, String codeVerifier) {
        @Override public String toString() { return "PcStatusRequest[<redacted>]"; }
    }
    public record PcClientEventRequest(String audience, UUID verificationId, String codeVerifier, ClientEventRequest event) {
        @Override public String toString() { return "PcClientEventRequest[<redacted>]"; }
    }
    public record PcReceipt(Receipt receipt, String nonce, String codeChallenge) { }
    public record ProofCodeResponse(String verificationCode, String audience, Instant expiresAt) {
        @Override public String toString() { return "ProofCodeResponse[verificationCode=<redacted>]"; }
    }
    public record ClientInfo(String pacsLoginId, String pacsAccountId, String computerName, String deviceId) {
        Map<String, String> details() {
            Map<String, String> result = new HashMap<>();
            metadata(result, "pacsLoginId", pacsLoginId);
            metadata(result, "pacsAccountId", pacsAccountId);
            metadata(result, "computerName", computerName);
            metadata(result, "deviceId", deviceId);
            result.put("clientInfoSource", "PACS_REPORTED");
            return result;
        }
        private static void metadata(Map<String, String> values, String field, String value) {
            if (value == null || value.isBlank()) return;
            if (value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
                throw ApiException.badRequest("INVALID_CLIENT_INFO", "Client identifiers must be at most 128 characters without control characters");
            }
            values.put(field, value.strip());
        }
    }
    public record RedeemRequest(String verificationCode, ClientInfo clientInfo) {
        public RedeemRequest(String verificationCode) { this(verificationCode, null); }
        @Override public String toString() { return "RedeemRequest[verificationCode=<redacted>]"; }
    }
    public record ClientEventRequest(UUID eventId, String action, ClientInfo clientInfo, String reportKeySha256) { }
    public record ClientEventResponse(boolean recorded, boolean duplicate) { }
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
        boolean pc = request.pcNonce() != null || request.codeChallenge() != null || request.codeChallengeMethod() != null;
        if (pc && (request.pcNonce() == null || !request.pcNonce().matches("[0-9a-f]{64}")
                || request.codeChallenge() == null || !CODE.matcher(request.codeChallenge()).matches()
                || !"S256".equals(request.codeChallengeMethod()))) {
            throw ApiException.unprocessable("PROOF_PKCE_INVALID", "S256 PC request binding is required");
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
        if (pc) payload += "\npcNonce=" + request.pcNonce() + "\ncodeChallengeMethod=S256\ncodeChallenge=" + request.codeChallenge();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        store.run(tx -> {
            tx.deleteExpired(CHALLENGES, now);
            if (tx.countLive(CHALLENGES, now) >= MAX_LIVE) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CERTIFICATE_CAPACITY_EXCEEDED",
                        "Too many certificate challenges", true);
            }
            tx.insert(CHALLENGES, new Challenge(id, client.clientId(), bytes, now, expiresAt,
                    expiresAt.plus(CHALLENGE_TTL), false, tenant, request.pcNonce(), request.codeChallenge()));
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
                    found.expiresAt(), found.retainUntil(), true, found.tenantId(), found.pcNonce(), found.codeChallenge()));
            if (!now.isBefore(found.expiresAt())) {
                record(tenant(clients.require(), found.tenantId()), found.clientId(), "DENIED", found.id().toString(),
                        AuditService.DENIED, context, Map.of("code", "CHALLENGE_EXPIRED", "stage", "VERIFY"));
                throw new ApiException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "Challenge expired");
            }
            return found;
        });
        String tenant = tenant(clients.require(), challenge.tenantId());
        Map<String, String> attempt = new HashMap<>();
        attempt.put("challengeId", challenge.id().toString());
        // Parse the submitted certificate for attribution only; verification below still decides success.
        try { attempt.put("fingerprintSha256", CertificateValidator.fingerprint(validator.parse(request.certificate()))); }
        catch (ApiException ignored) { /* malformed certificate still counts as an unattributed attempt */ }
        record(tenant, challenge.clientId(), "ATTEMPTED", challenge.id().toString(), AuditService.SUCCESS, context, attempt);
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
            attempt.put("issuerDn", leaf.getIssuerX500Principal().getName());
            attempt.put("revocationStatus", validation.revocation().name());
            String points = com.inviz.visioncerti.certificate.YessignCrlResolver.distributionPoints(leaf).stream()
                    .map(Object::toString).collect(java.util.stream.Collectors.joining(" | "));
            if (!points.isEmpty()) attempt.put("crlDistributionPoints", points);
            CertificateValidator.requireGood(validation.revocation());
        } catch (ApiException failure) {
            Map<String, String> denial = new HashMap<>(attempt);
            denial.put("code", failure.code()); denial.put("stage", "VERIFY");
            record(tenant, challenge.clientId(), "DENIED", challenge.id().toString(), AuditService.DENIED, context,
                    denial);
            throw failure;
        }
        String code = randomBase64();
        Instant verifiedAt = clock.instant();
        Proof proof = new Proof(UUID.randomUUID(), challenge.clientId(), Hashing.sha256Hex(code), fingerprint,
                leaf.getSubjectX500Principal().getName(), leaf.getIssuerX500Principal().getName(),
                leaf.getNotAfter().toInstant(), request.certificate(), List.copyOf(request.chain() == null
                        ? List.of() : request.chain()), verifiedAt, verifiedAt.plus(CODE_TTL),
                verifiedAt.plus(PROOF_TTL), null, tenant, challenge.pcNonce(), challenge.codeChallenge(), null);
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
        Map<String, String> clientInfo = request == null || request.clientInfo() == null ? Map.of() : request.clientInfo().details();
        if (code == null || !CODE.matcher(code).matches()) throw invalidCode();
        Instant now = clock.instant();
        Proof proof = store.tx(tx -> {
            Proof found = tx.lockFindOne(PROOFS, "code_hash", Hashing.sha256Hex(code)).orElse(null);
            if (found == null || found.codeChallenge() != null || found.redeemedAt() != null || !found.clientId().equals(client.clientId())
                    || !now.isBefore(found.codeExpiresAt())) throw invalidCode();
            tx.update(PROOFS, new Proof(found.id(), found.clientId(), found.codeHash(), found.fingerprint(),
                    found.subjectDn(), found.issuerDn(), found.certificateNotAfter(), found.certificateDer(),
                    found.chainDer(), found.verifiedAt(), found.codeExpiresAt(), found.expiresAt(), now, found.tenantId(),
                    found.pcNonce(), found.codeChallenge(), found.lease()));
            return found;
        });
        Receipt receipt = statusChecked(client, proof.id());
        Map<String, String> details = evidence(proof);
        details.putAll(clientInfo);
        record(tenant(client, proof.tenantId()), client.clientId(), "REDEEMED", proof.id().toString(),
                AuditService.SUCCESS, context, details);
        return receipt;
    }

    /** HMAC-authenticated PACS observations. These records never grant login or report approval. */
    public ClientEventResponse clientEvent(IntegrationClient client, UUID verificationId, ClientEventRequest request,
            PacsRequestContext context) {
        if (request == null || request.eventId() == null || request.clientInfo() == null || request.action() == null
                || !List.of("LOGIN_ATTEMPTED", "LOGIN_SUCCEEDED", "LOGIN_FAILED", "APPROVAL_REQUESTED").contains(request.action())) {
            throw ApiException.badRequest("INVALID_CLIENT_EVENT", "A supported action, eventId and clientInfo are required");
        }
        Map<String, String> details = new HashMap<>(request.clientInfo().details());
        if (!details.containsKey("pacsLoginId") || !details.containsKey("deviceId")) {
            throw ApiException.badRequest("INVALID_CLIENT_EVENT", "pacsLoginId and deviceId are required");
        }
        if ("APPROVAL_REQUESTED".equals(request.action())) {
            if (request.reportKeySha256() == null || !request.reportKeySha256().matches("[a-f0-9]{64}")) {
                throw ApiException.badRequest("INVALID_CLIENT_EVENT", "A SHA-256 report identifier is required");
            }
            details.put("reportKeySha256", request.reportKeySha256());
        } else if (request.reportKeySha256() != null) {
            throw ApiException.badRequest("INVALID_CLIENT_EVENT", "Report identifier is only allowed for approval requests");
        }
        Proof proof = store.tx(tx -> tx.get(PROOFS, verificationId).orElse(null));
        if (proof != null && (!proof.clientId().equals(client.clientId()) || proof.redeemedAt() == null)) {
            throw ApiException.forbidden("CERTIFICATE_PROOF_INACTIVE", "A redeemed proof for this PACS is required");
        }
        // Operational proofs expire; the immutable redeem record still allows a delayed audit outbox delivery.
        var historical = proof == null ? client.tenantIds().stream().flatMap(t -> audit.list(t).stream())
                .filter(e -> "PACS_CERTIFICATE_PROOF_REDEEMED".equals(e.action())
                        && verificationId.toString().equals(e.targetId()) && client.clientId().equals(e.details().get("clientId")))
                .findFirst().orElse(null) : null;
        if (proof == null && historical == null) throw ApiException.forbidden("CERTIFICATE_PROOF_INACTIVE", "Redeemed proof not found");
        String scope = proof != null ? tenant(client, proof.tenantId()) : historical.tenantId();
        if (proof != null) details.putAll(evidence(proof));
        else for (String key : List.of("verificationId", "fingerprintSha256", "subjectDn", "issuerDn", "certificateNotAfter", "verifiedAt", "expiresAt", "exchangeMethod")) {
            if (historical.details().containsKey(key)) details.put(key, historical.details().get(key));
        }
        details.put("eventId", request.eventId().toString());
        var identityDetails = new java.util.TreeMap<>(details);
        // Transport metadata is not part of the client event. Keep pre-SP10 event retries compatible.
        identityDetails.remove("exchangeMethod");
        String identity = Hashing.sha256Hex(identityDetails.toString() + request.action());
        // Lock and append in the same DB transaction: retries and concurrent submissions count only once.
        return store.tx(tx -> {
            tx.lock("pacs-client-event|" + scope + "|" + client.clientId() + "|" + request.eventId());
            var previous = audit.list(scope).stream().filter(e -> client.clientId().equals(e.details().get("clientId"))
                    && request.eventId().toString().equals(e.details().get("eventId"))).findFirst();
            if (previous.isPresent()) {
                if (!identity.equals(previous.get().details().get("eventFingerprint"))) {
                    throw ApiException.conflict("CLIENT_EVENT_REUSED", "eventId was used with different details");
                }
                return new ClientEventResponse(true, true);
            }
            // Queued observations may arrive after expiry; they remain client-reported history, not authorization.
            details.put("eventFingerprint", identity);
            record(scope, client.clientId(), request.action(), verificationId.toString(),
                    "LOGIN_FAILED".equals(request.action()) ? AuditService.DENIED : AuditService.SUCCESS, context, details);
            return new ClientEventResponse(true, false);
        });
    }

    public Receipt status(IntegrationClient client, UUID verificationId) {
        return status(client, verificationId, PacsRequestContext.EMPTY);
    }

    /** Public PC redemption: possession of a fresh certificate AND the originating PC verifier is required.
     * No integration HMAC key is sent to a PC. Only the S256 hash is retained by the server.
     */
    public PcReceipt redeemPc(PcRedeemRequest request, PacsRequestContext context) {
        try {
            return redeemPcChecked(request, context);
        } catch (ApiException failure) {
            recordUnboundDenial(context, "PC_REDEEM", failure.code());
            throw failure;
        }
    }

    private PcReceipt redeemPcChecked(PcRedeemRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        if (request == null || !client.clientId().equals(request.audience())
                || request.verificationCode() == null || !CODE.matcher(request.verificationCode()).matches()) throw invalidCode();
        Map<String, String> clientInfo = request.clientInfo() == null ? Map.of() : request.clientInfo().details();
        Instant now = clock.instant();
        Proof proof = store.tx(tx -> {
            Proof found = tx.lockFindOne(PROOFS, "code_hash", Hashing.sha256Hex(request.verificationCode())).orElse(null);
            if (found == null || found.redeemedAt() != null || !found.clientId().equals(client.clientId())
                    || !now.isBefore(found.codeExpiresAt()) || !pkceMatches(found, request.codeVerifier())
                    || !constantEquals(found.pcNonce(), request.nonce())) throw invalidCode();
            tx.update(PROOFS, new Proof(found.id(), found.clientId(), found.codeHash(), found.fingerprint(),
                    found.subjectDn(), found.issuerDn(), found.certificateNotAfter(), found.certificateDer(),
                    found.chainDer(), found.verifiedAt(), found.codeExpiresAt(), found.expiresAt(), now, found.tenantId(),
                    found.pcNonce(), found.codeChallenge(), found.lease()));
            return found;
        });
        Receipt receipt = statusChecked(client, proof.id());
        Map<String, String> details = evidence(proof);
        details.putAll(clientInfo);
        record(tenant(client, proof.tenantId()), client.clientId(), "REDEEMED", proof.id().toString(),
                AuditService.SUCCESS, context, details);
        return new PcReceipt(receipt, proof.pcNonce(), proof.codeChallenge());
    }

    /** The verifier is a per-proof capability, never a bearer credential for any other proof/account. */
    public Receipt statusPc(PcStatusRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        Proof proof;
        try {
            if (request == null) throw invalidCode();
            proof = requirePcProof(client, request.audience(), request.verificationId(), request.codeVerifier());
        } catch (ApiException failure) {
            recordUnboundDenial(context, "PC_STATUS", failure.code());
            throw failure;
        }
        return status(client, proof.id(), context);
    }

    /** Optional PC telemetry. A proof-scoped verifier can report observations, never grant approval. */
    public ClientEventResponse clientEventPc(PcClientEventRequest request, PacsRequestContext context) {
        try {
            IntegrationClient client = clients.require();
            if (request == null) throw invalidCode();
            Proof proof = requirePcProof(client, request.audience(), request.verificationId(), request.codeVerifier());
            // A public PC capability is not accepted after its two-hour lifetime.
            if (proof.redeemedAt() == null || !clock.instant().isBefore(proof.expiresAt())) {
                throw ApiException.forbidden("CERTIFICATE_PROOF_INACTIVE", "A live redeemed PC proof is required");
            }
            return clientEvent(client, proof.id(), request.event(), context);
        } catch (ApiException failure) {
            recordUnboundDenial(context, "PC_EVENT", failure.code());
            throw failure;
        }
    }

    private Proof requirePcProof(IntegrationClient client, String audience, UUID id, String verifier) {
        if (id == null || !client.clientId().equals(audience)) throw invalidCode();
        Proof proof = store.tx(tx -> tx.get(PROOFS, id).orElse(null));
        if (proof == null || !proof.clientId().equals(client.clientId()) || !pkceMatches(proof, verifier)) throw invalidCode();
        return proof;
    }

    private static boolean constantEquals(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean pkceMatches(Proof proof, String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) return false;
        try {
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return constantEquals(proof.codeChallenge(), challenge);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
                || !clock.instant().isBefore(proof.expiresAt()) || !ownerOnline(proof, clock.instant())) {
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

    /** One account lease is shared using a credential-derived capability, never a public login ID. */
    public AccountStatus activateAccount(AccountActivateRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        if (request == null) throw invalidCode();
        String key = accountKeyHash(request.accountKey());
        requireAccountInfo(request.clientInfo(), true);
        if (request.bootId() == null || !request.bootId().matches("[0-9a-f]{64}")) throw invalidCode();
        Proof proof = requirePcProof(client, request.audience(), request.verificationId(), request.codeVerifier());
        statusChecked(client, proof.id()); // Network validation outside the storage transaction.
        Instant now = clock.instant();
        Proof activated = store.tx(tx -> {
            tx.lock("pacs-account|" + client.clientId() + "|" + key);
            Proof found = tx.lockGet(PROOFS, proof.id()).orElseThrow(PacsCertificateProofService::invalidCode);
            if (found.lease() != null) {
                if (!constantEquals(found.lease().accountKeyHash(), key)
                        || !constantEquals(found.lease().bootId(), request.bootId())
                        || !found.lease().owner().equals(request.clientInfo()) || !ownerOnline(found, now)) throw invalidCode();
                return found; // Retry never moves the initial authentication time or expiry.
            }
            if (found.redeemedAt() == null || !now.isBefore(found.verifiedAt().plus(Duration.ofMinutes(5)))) throw invalidCode();
            for (Proof old : tx.find(PROOFS, "client_id", client.clientId())) {
                if (old.lease() != null && key.equals(old.lease().accountKeyHash())) {
                    Proof locked = tx.lockGet(PROOFS, old.id()).orElseThrow();
                    tx.update(PROOFS, withLease(locked, locked.expiresAt(), new AccountLease(key,
                            locked.lease().owner(), locked.lease().bootId(), locked.lease().lastSeenAt(), true)));
                }
            }
            Instant expiry = found.verifiedAt().plus(ACCOUNT_TTL);
            if (expiry.isAfter(found.certificateNotAfter())) expiry = found.certificateNotAfter();
            Proof next = withLease(found, expiry, new AccountLease(key, request.clientInfo(), request.bootId(), now, false));
            tx.update(PROOFS, next);
            Map<String, String> details = evidence(next);
            details.putAll(request.clientInfo().details());
            details.put("policy", "ACCOUNT_72H_OR_OWNER_OFFLINE");
            record(tenant(client, next.tenantId()), client.clientId(), "ACCOUNT_ACTIVATED", next.id().toString(),
                    AuditService.SUCCESS, context, details);
            return next;
        });
        return accountResponse(activated, now);
    }

    public AccountStatus accountStatus(AccountStatusRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        Proof proof = accountProof(client, request);
        Instant now = clock.instant();
        AccountStatus state = accountResponse(proof, now);
        if (state.active()) {
            statusChecked(client, proof.id()); // Includes live revocation/validity checks.
            Proof latest = store.tx(tx -> tx.get(PROOFS, proof.id()).orElse(null));
            return accountResponse(latest, clock.instant());
        }
        return state;
    }

    public AccountStatus heartbeatAccount(AccountHeartbeatRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        if (request == null) throw invalidCode();
        Proof proof = requirePcProof(client, request.audience(), request.verificationId(), request.codeVerifier());
        Instant now = clock.instant();
        Proof updated = store.tx(tx -> {
            Proof found = tx.lockGet(PROOFS, proof.id()).orElseThrow(PacsCertificateProofService::invalidCode);
            AccountLease lease = found.lease();
            if (lease == null || !constantEquals(lease.bootId(), request.bootId())
                    || !constantEquals(lease.owner().deviceId(), request.deviceId())) throw invalidCode();
            if (!ownerOnline(found, now) || !now.isBefore(found.expiresAt())) return found;
            Proof next = withLease(found, found.expiresAt(), new AccountLease(lease.accountKeyHash(), lease.owner(),
                    lease.bootId(), now, request.close()));
            tx.update(PROOFS, next);
            if (request.close()) {
                Map<String, String> details = evidence(next);
                details.putAll(lease.owner().details());
                record(tenant(client, next.tenantId()), client.clientId(), "ACCOUNT_ENDED", next.id().toString(),
                        AuditService.SUCCESS, context, details);
            }
            return next;
        });
        return accountResponse(updated, now);
    }

    public ClientEventResponse accountEvent(AccountEventRequest request, PacsRequestContext context) {
        IntegrationClient client = clients.require();
        if (request == null || request.event() == null) throw invalidCode();
        Proof proof = accountProof(client, request.account());
        if (!accountResponse(proof, clock.instant()).active()) throw invalidCode();
        if (!request.account().clientInfo().equals(request.event().clientInfo())) throw invalidCode();
        statusChecked(client, proof.id());
        return clientEvent(client, proof.id(), request.event(), context);
    }

    private Proof accountProof(IntegrationClient client, AccountStatusRequest request) {
        if (request == null || !client.clientId().equals(request.audience())) throw invalidCode();
        String key = accountKeyHash(request.accountKey());
        requireAccountInfo(request.clientInfo(), false);
        return store.tx(tx -> tx.find(PROOFS, "client_id", client.clientId()).stream()
                .filter(p -> p.lease() != null && constantEquals(p.lease().accountKeyHash(), key)
                        && p.lease().owner().pacsLoginId().equals(request.clientInfo().pacsLoginId())
                        && (request.clientInfo().pacsAccountId() == null || request.clientInfo().pacsAccountId().isBlank()
                            || p.lease().owner().pacsAccountId().equals(request.clientInfo().pacsAccountId())))
                .max(java.util.Comparator.comparing(Proof::verifiedAt)).orElse(null));
    }

    private static String accountKeyHash(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw invalidCode();
        return Hashing.sha256Hex("vision-certi-account-v1|" + key);
    }

    private static void requireAccountInfo(ClientInfo info, boolean activation) {
        if (info == null) throw invalidCode();
        Map<String, String> fields = info.details();
        if (!fields.containsKey("pacsLoginId") || (activation && !fields.containsKey("pacsAccountId"))
                || !fields.containsKey("deviceId")) throw invalidCode();
    }

    private static boolean ownerOnline(Proof proof, Instant now) {
        return proof.lease() == null || (!proof.lease().ended()
                && now.isBefore(proof.lease().lastSeenAt().plus(OWNER_TIMEOUT)));
    }

    private AccountStatus accountResponse(Proof proof, Instant now) {
        if (proof == null || proof.lease() == null) return new AccountStatus(false, "NOT_AUTHENTICATED", now,
                null, null, null, null, null);
        boolean expired = !now.isBefore(proof.expiresAt());
        boolean online = ownerOnline(proof, now);
        boolean active = !expired && online;
        return new AccountStatus(active, active ? "ACTIVE" : expired ? "EXPIRED" : "SOURCE_OFFLINE", now,
                proof.verifiedAt(), proof.expiresAt(), proof.lease().lastSeenAt().plus(OWNER_TIMEOUT),
                proof.lease().owner().computerName(), active ? receipt(proof) : null);
    }

    private static Proof withLease(Proof p, Instant expiry, AccountLease lease) {
        return new Proof(p.id(), p.clientId(), p.codeHash(), p.fingerprint(), p.subjectDn(), p.issuerDn(),
                p.certificateNotAfter(), p.certificateDer(), p.chainDer(), p.verifiedAt(), p.codeExpiresAt(),
                expiry, p.redeemedAt(), p.tenantId(), p.pcNonce(), p.codeChallenge(), lease);
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
        details.put("exchangeMethod", proof.codeChallenge() == null ? "SERVER_HMAC" : "PC_S256");
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
