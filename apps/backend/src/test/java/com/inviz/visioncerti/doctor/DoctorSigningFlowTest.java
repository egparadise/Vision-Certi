package com.inviz.visioncerti.doctor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.delivery.DeliveryService;
import com.inviz.visioncerti.doctor.DoctorDtos.ChallengeResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregisterDoctor;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregistrationResponse;
import com.inviz.visioncerti.doctor.credential.DoctorCredential;
import com.inviz.visioncerti.doctor.credential.DoctorCredentialProvider;
import com.inviz.visioncerti.doctor.credential.FakeOpenBao;
import com.inviz.visioncerti.doctor.credential.OpenBaoCredentialProvider;
import com.inviz.visioncerti.doctor.identity.TestGoogleTokens;
import com.inviz.visioncerti.document.DocumentDtos.DocumentMetadata;
import com.inviz.visioncerti.document.DocumentDtos.DocumentResponse;
import com.inviz.visioncerti.document.DocumentRepository;
import com.inviz.visioncerti.document.DocumentService;
import com.inviz.visioncerti.security.ActorDirectory;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.MockSigningProvider;
import com.inviz.visioncerti.signing.Signature;
import com.inviz.visioncerti.signing.SignRequestService;
import com.inviz.visioncerti.signing.SignRequestStatus;
import com.inviz.visioncerti.signing.SignatureService;
import com.inviz.visioncerti.signing.SigningDtos.ApproveSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.CheckResult;
import com.inviz.visioncerti.signing.SigningDtos.CreateSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.SignRequestResponse;
import com.inviz.visioncerti.signing.SigningDtos.VerificationResponse;
import com.inviz.visioncerti.signing.SigningRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end physician signing through the real OpenBao adapter (against the loopback fake): activation provisions
 * a Transit key + PKI certificate, approval signs the SHA-256 of the exact stored bytes, and every refusal path
 * (replay, stale/tampered document, revoked certificate, wrong signer, admin) fails closed.
 */
class DoctorSigningFlowTest {

    private static final String TENANT = "tenant-a";
    private static final RequestActor ADMIN = new RequestActor(TENANT, "admin-1", ActorRole.ADMIN);
    private static final RequestActor DEV_DOCTOR = new RequestActor(TENANT, "doctor-1", ActorRole.DOCTOR);
    private static final ConsentRequest CONSENT = new ConsentRequest("2026-10-13-draft", true, true, true);

    private final ActorDirectory directory = new ActorDirectory() {
        @Override
        public List<Tenant> tenants() {
            return List.of(new Tenant(TENANT, "Tenant A"));
        }

        @Override
        public Optional<Member> member(String tenantId, String actorId) {
            if (!TENANT.equals(tenantId)) {
                return Optional.empty();
            }
            return switch (actorId) {
                case "admin-1" -> Optional.of(new Member("admin-1", "Admin", ActorRole.ADMIN));
                case "doctor-1" -> Optional.of(new Member("doctor-1", "Dev doctor", ActorRole.DOCTOR));
                default -> Optional.empty();
            };
        }
    };

    private FakeOpenBao bao;
    private MutableClock clock;
    private AuditService audit;
    private TestGoogleTokens google;
    private DoctorService doctors;
    private DocumentService documents;
    private SignRequestService signRequests;
    private SignatureService signatures;
    private SigningRepository signingRepository;
    /** Runs once inside the next remote sign call, after all pre-sign checks passed. */
    private volatile Runnable beforeRemoteSign;

    @BeforeEach
    void setUp() throws Exception {
        bao = new FakeOpenBao();
        clock = new MutableClock(Instant.now());
        audit = new AuditService(clock);
        google = new TestGoogleTokens(clock);
        OpenBaoCredentialProvider openBao = new OpenBaoCredentialProvider(bao.properties(), clock);
        DoctorCredentialProvider hooked = new DoctorCredentialProvider() {
            @Override
            public String backend() {
                return openBao.backend();
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String unavailableReason() {
                return null;
            }

            @Override
            public String signatureMode() {
                return openBao.signatureMode();
            }

            @Override
            public boolean cryptographicallyAttributable() {
                return true;
            }

            @Override
            public boolean checksRevocationRemotely() {
                return true;
            }

            @Override
            public DoctorCredential provision(String doctorId) {
                return openBao.provision(doctorId);
            }

            @Override
            public byte[] signDigest(DoctorCredential credential, byte[] sha256Digest) {
                Runnable hook = beforeRemoteSign;
                beforeRemoteSign = null;
                if (hook != null) {
                    hook.run();
                }
                return openBao.signDigest(credential, sha256Digest);
            }

            @Override
            public void assertNotRevoked(DoctorCredential credential) {
                openBao.assertNotRevoked(credential);
            }

            @Override
            public void revoke(DoctorCredential credential) {
                openBao.revoke(credential);
            }
        };
        // Google-only activation under the dev GOOGLE_PERMITTED_DEV policy; certificate gating is covered elsewhere.
        doctors = new DoctorService(audit, hooked, google.verifier(), clock, DoctorPolicy.defaults(), false, false);
        DocumentRepository documentRepository = new DocumentRepository();
        documents = new DocumentService(documentRepository, directory, doctors, audit, clock, 1_000_000);
        signingRepository = new SigningRepository();
        MockSigningProvider legacy = new MockSigningProvider();
        signRequests = new SignRequestService(signingRepository, documentRepository, directory, doctors, legacy,
                new DeliveryService(audit, clock), audit, clock, Duration.ofMinutes(10));
        signatures = new SignatureService(signingRepository, documentRepository, legacy, audit, clock);
    }

    @AfterEach
    void tearDown() {
        bao.close();
    }

    private DoctorPrincipal activate(String email, String subject) {
        PreregistrationResponse pre = doctors.preregister(ADMIN, new PreregisterDoctor("김의사",
                Integer.toString(email.hashCode() & 0xffff), "서울영상센터", email, "evidence://id", "evidence://lic"));
        ChallengeResponse c = doctors.createActivationChallenge(pre.invitationCode(), "10.0.0.1");
        DoctorPrincipal p = doctors.authenticate("Bearer " + doctors.completeGoogle(c.challengeId(),
                google.token(subject, email, c.nonce())).activationToken());
        doctors.consent(p, CONSENT);
        DoctorResponse active = doctors.activate(p);
        assertThat(active.certificate().kind()).isEqualTo("X509");
        return p;
    }

    private static byte[] pdf(String body) {
        return ("%PDF-1.7\n" + body + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    private DocumentResponse upload(String externalId, String signerId, byte[] content) {
        return documents.register(ADMIN, new DocumentMetadata(externalId, "study-" + externalId, "Report", signerId),
                content).document();
    }

    private SignRequestResponse request(DocumentResponse doc) {
        return signRequests.create(ADMIN, new CreateSignRequest(doc.latestVersion().id(), doc.signerId()));
    }

    private static String code(Throwable t) {
        return ((ApiException) t).code();
    }

    @Test
    void physicianSignsExactStoredBytesWithCertifiedTransitKey() throws Exception {
        DoctorPrincipal p = activate("kim@example.com", "google-sub-kim");
        String doctorId = p.doctorId().toString();
        byte[] content = pdf("findings: normal");
        DocumentResponse doc = upload("report-1", doctorId, content);
        SignRequestResponse req = request(doc);
        assertThat(req.signatureMode()).isEqualTo(OpenBaoCredentialProvider.SIGNATURE_MODE);
        // Only the bound physician sees the nonce.
        assertThat(signRequests.get(ADMIN, req.id()).nonce()).isNull();
        assertThat(signRequests.get(DEV_DOCTOR, req.id()).nonce()).isNull();
        SignRequestResponse mine = signRequests.getForDoctor(TENANT, doctorId, req.id());
        assertThat(mine.nonce()).isNotNull();

        SignRequestResponse done = signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest(req.documentSha256(), mine.nonce()));

        assertThat(done.status()).isEqualTo(SignRequestStatus.COMPLETED);
        Map<String, Object> transitCall = bao.signBodies.getLast();
        assertThat(transitCall.get("input")).isEqualTo(Base64.getEncoder().encodeToString(Hashing.sha256(content)));
        assertThat(transitCall.get("key_version")).isEqualTo(1);

        Signature signature = signingRepository.findSignature(TENANT, done.signatureId()).orElseThrow();
        assertThat(signature.signerId()).isEqualTo(doctorId);
        assertThat(signature.clinicalUseAllowed()).isFalse();
        var evidence = signature.doctorEvidence();
        assertThat(evidence.doctorId()).isEqualTo(doctorId);
        assertThat(evidence.identityMode()).isEqualTo("GOOGLE");
        assertThat(evidence.identitySubject()).isEqualTo("google-sub-kim");
        assertThat(evidence.identityIssuer()).isEqualTo("https://accounts.google.com");
        assertThat(evidence.keyName()).isEqualTo("vc-doctor-" + doctorId);
        assertThat(evidence.keyVersion()).isEqualTo(1);
        assertThat(evidence.reportId()).isEqualTo("report-1");
        assertThat(evidence.documentSha256()).isEqualTo(Hashing.sha256Hex(content));
        assertThat(evidence.documentVersionId()).isEqualTo(doc.latestVersion().id());
        assertThat(evidence.signatureFormat()).isEqualTo("RAW_ECDSA_P256_SHA256_DER");
        assertThat(evidence.revocationCheckedAtSigning()).isTrue();
        AuditEvent approval = audit.list(TENANT).stream().filter(e -> e.id().equals(evidence.auditId())).findFirst()
                .orElseThrow();
        assertThat(approval.action()).isEqualTo("SIGN_REQUEST_APPROVED");
        assertThat(approval.details().get("approvalId")).isEqualTo(evidence.approvalId().toString());

        // The signature verifies with the X.509 certificate issued at activation.
        DoctorResponse doctor = doctors.get(ADMIN, p.doctorId());
        assertThat(evidence.certificateId()).isEqualTo(doctor.certificate().serialNumber());
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(doctor.certificate().certificatePem().getBytes(StandardCharsets.US_ASCII)));
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(cert.getPublicKey());
        verifier.update(content);
        assertThat(verifier.verify(Base64.getDecoder().decode(signature.signatureValue()))).isTrue();

        VerificationResponse verification = signatures.verify(ADMIN, signature.id());
        assertThat(verification.checks().documentIntegrity()).isEqualTo(CheckResult.VALID);
        assertThat(verification.checks().signature()).isEqualTo(CheckResult.VALID);
        assertThat(verification.checks().revocationAtSigning()).isEqualTo(CheckResult.VALID);
        assertThat(verification.checks().trustChain()).isEqualTo(CheckResult.INDETERMINATE);
        assertThat(verification.checks().timestamp()).isEqualTo(CheckResult.INDETERMINATE);
        assertThat(verification.status()).isEqualTo(CheckResult.INDETERMINATE);
        assertThat(verification.clinicalUseAllowed()).isFalse();
        assertThat(verification.reasonCodes()).contains("PRIVATE_CA_NOT_TRUSTED", "MISSING_TRUSTED_TIMESTAMP",
                "RAW_ECDSA_NOT_CADES");

        // Replay of the consumed nonce.
        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest(req.documentSha256(), mine.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("NONCE_ALREADY_USED"));
    }

    @Test
    void staleTamperedOrForeignApprovalsAreRefused() {
        DoctorPrincipal p = activate("lee@example.com", "google-sub-lee");
        DoctorPrincipal other = activate("park@example.com", "google-sub-park");
        String doctorId = p.doctorId().toString();
        DocumentResponse doc = upload("report-2", doctorId, pdf("v1"));
        SignRequestResponse req = request(doc);
        String nonce = signRequests.getForDoctor(TENANT, doctorId, req.id()).nonce();

        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(other), req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGNER_MISMATCH"));
        assertThatThrownBy(() -> signRequests.getForDoctor(TENANT, other.doctorId().toString(), req.id()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGN_REQUEST_NOT_FOUND"));
        assertThatThrownBy(() -> signRequests.approve(ADMIN, req.id(), new ApproveSignRequest(req.documentSha256(),
                nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGNING_NOT_PERMITTED_FOR_ROLE"));
        assertThatThrownBy(() -> signRequests.approve(DEV_DOCTOR, req.id(), new ApproveSignRequest(
                req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGNER_MISMATCH"));
        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest("0".repeat(64), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCUMENT_HASH_MISMATCH"));

        documents.addVersion(ADMIN, doc.id(), doc.latestVersion().id(), "corrected", pdf("v2"));
        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isIn("DOCUMENT_VERSION_SUPERSEDED", "INVALID_STATE"));
        assertThat(signingRepository.listSignatures(TENANT)).isEmpty();
    }

    @Test
    void certificateRevokedInPkiOrSuspendedDoctorCannotSign() {
        DoctorPrincipal p = activate("choi@example.com", "google-sub-choi");
        String doctorId = p.doctorId().toString();
        SignRequestResponse req = request(upload("report-3", doctorId, pdf("x")));
        String nonce = signRequests.getForDoctor(TENANT, doctorId, req.id()).nonce();

        String serial = doctors.get(ADMIN, p.doctorId()).certificate().serialNumber();
        bao.revoked.put(serial, Instant.now().getEpochSecond());
        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_VALID"));
        assertThat(audit.list(TENANT).getFirst().outcome()).isEqualTo(AuditService.DENIED);
        // Refusal did not consume the nonce; after the CA un-revokes (test only) the same approval works.
        bao.revoked.put(serial, 0L);
        doctors.changeStatus(ADMIN, p.doctorId(), new DoctorDtos.ChangeDoctorStatus("SUSPENDED", "조사 중"));
        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_ACTIVE"));
        assertThatThrownBy(() -> request(upload("report-4", doctorId, pdf("y"))))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGNER_NOT_ELIGIBLE"));
        assertThat(signingRepository.listSignatures(TENANT)).isEmpty();
    }

    @Test
    void revocationDuringRemoteSigningDiscardsTheSignature() {
        DoctorPrincipal p = activate("han@example.com", "google-sub-han");
        String doctorId = p.doctorId().toString();
        SignRequestResponse req = request(upload("report-5", doctorId, pdf("race")));
        String nonce = signRequests.getForDoctor(TENANT, doctorId, req.id()).nonce();
        String serial = doctors.get(ADMIN, p.doctorId()).certificate().serialNumber();

        // All pre-sign checks pass; the admin revokes while Transit is producing the signature.
        beforeRemoteSign = () -> doctors.changeStatus(ADMIN, p.doctorId(),
                new DoctorDtos.ChangeDoctorStatus("REVOKED", "서명 중 폐지"));
        assertThatThrownBy(() -> signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_ACTIVE"));

        assertThat(signingRepository.listSignatures(TENANT)).isEmpty();
        assertThat(signRequests.get(ADMIN, req.id()).status()).isEqualTo(SignRequestStatus.FAILED);
        assertThat(doctors.get(ADMIN, p.doctorId()).status()).isEqualTo(DoctorStatus.REVOKED);
        assertThat(doctors.get(ADMIN, p.doctorId()).certificate().status()).isEqualTo("REVOKED");
        assertThat(bao.revoked.get(serial)).isPositive();
    }

    @Test
    void concurrentApprovalsProduceExactlyOneSignature() throws Exception {
        DoctorPrincipal p = activate("yoon@example.com", "google-sub-yoon");
        String doctorId = p.doctorId().toString();
        SignRequestResponse req = request(upload("report-6", doctorId, pdf("once")));
        String nonce = signRequests.getForDoctor(TENANT, doctorId, req.id()).nonce();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return signRequests.approveAsRegisteredDoctor(doctors.signerFor(p), req.id(),
                                new ApproveSignRequest(req.documentSha256(), nonce)).status().name();
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).containsOnlyOnce("COMPLETED");
            assertThat(outcomes.stream().filter("NONCE_ALREADY_USED"::equals)).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(signingRepository.listSignatures(TENANT)).hasSize(1);
    }

    @Test
    void revocationAsksPkiToRevoke() {
        DoctorPrincipal p = activate("jung@example.com", "google-sub-jung");
        String serial = doctors.get(ADMIN, p.doctorId()).certificate().serialNumber();
        doctors.changeStatus(ADMIN, p.doctorId(), new DoctorDtos.ChangeDoctorStatus("REVOKED", "면허 취소"));
        assertThat(bao.revoked.get(serial)).isPositive();
    }
}
