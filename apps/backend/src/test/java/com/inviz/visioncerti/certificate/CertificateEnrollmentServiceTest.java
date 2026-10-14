package com.inviz.visioncerti.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.certificate.CertificateDtos.CertificateSessionResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.EnrollmentResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.LoginRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileStartResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ReviewRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.VerifyRequest;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorDtos.ChangeDoctorStatus;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregisterDoctor;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregistrationResponse;
import com.inviz.visioncerti.doctor.DoctorPolicy;
import com.inviz.visioncerti.doctor.DoctorPrincipal;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.DoctorStatus;
import com.inviz.visioncerti.doctor.credential.DevSimulatedCredentialProvider;
import com.inviz.visioncerti.doctor.credential.DoctorCredential;
import com.inviz.visioncerti.signing.RegisteredDoctorSigner;
import com.inviz.visioncerti.doctor.identity.TestGoogleTokens;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/**
 * Certificate enrollment against a synthetic PKI (root → issuing CA → leaf) with real signatures and CRLs, under the
 * default CERTIFICATE_REQUIRED policy.
 */
class CertificateEnrollmentServiceTest {

    private static final String TENANT = "tenant-a";
    private static final RequestActor ADMIN = new RequestActor(TENANT, "admin-1", ActorRole.ADMIN);
    private static final RequestActor OTHER_ADMIN = new RequestActor("tenant-b", "admin-2", ActorRole.ADMIN);
    private static final RequestActor DOCTOR_ROLE = new RequestActor(TENANT, "doctor-demo", ActorRole.DOCTOR);
    private static final ConsentRequest CONSENT = new ConsentRequest("2026-10-13-draft", true, true, true);

    /** Mutable in-memory trust material. */
    static final class TestTrust implements CertificateTrustSource {
        final List<X509Certificate> anchors = new ArrayList<>();
        final List<X509CRL> crls = new ArrayList<>();

        @Override
        public List<X509Certificate> trustAnchors() {
            return List.copyOf(anchors);
        }

        @Override
        public List<X509CRL> crls() {
            return List.copyOf(crls);
        }
    }

    private MutableClock clock;
    private AuditService audit;
    private TestGoogleTokens google;
    private DoctorService doctors;
    private TestPki pki;
    private TestTrust trust;
    private FakeMobileProvider fakeMobile;
    private CertificateEnrollmentService service;
    private final AtomicInteger licenses = new AtomicInteger(7000);
    /** Runs inside the (simulated) remote signing call, to model a concurrent state change. */
    private Runnable duringRemoteSign = () -> { };

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        audit = new AuditService(clock);
        google = new TestGoogleTokens(clock);
        DevSimulatedCredentialProvider credentials = new DevSimulatedCredentialProvider(clock) {
            @Override
            public synchronized byte[] signDigest(DoctorCredential credential, byte[] sha256Digest) {
                duringRemoteSign.run();
                return super.signDigest(credential, sha256Digest);
            }
        };
        doctors = new DoctorService(audit, credentials, google.verifier(), clock, DoctorPolicy.defaults(), false, true);
        pki = new TestPki(clock, "Joint");
        trust = new TestTrust();
        trust.anchors.add(pki.root.certificate());
        trust.crls.addAll(pki.freshCrls());
        fakeMobile = new FakeMobileProvider();
        service = new CertificateEnrollmentService(doctors, new CertificateValidator(trust, clock), fakeMobile, null,
                audit, clock, "svc-test", Duration.ofMinutes(5));
    }

    // ------------------------------------------------------------------ helpers

    private PreregistrationResponse preregister(String email) {
        return preregister(email, null);
    }

    private PreregistrationResponse preregister(String email, String expectedFingerprint) {
        return doctors.preregister(ADMIN, new PreregisterDoctor("김의사", Integer.toString(licenses.incrementAndGet()),
                "서울영상센터", email, "evidence://id", "evidence://license", expectedFingerprint));
    }

    private ChallengeResponse challenge(String invitationCode) {
        return service.createEnrollmentChallenge(invitationCode, null, "10.0.0.1");
    }

    private static byte[] payload(ChallengeResponse c) {
        return Base64.getDecoder().decode(c.signedPayloadBase64());
    }

    private VerifyRequest proof(ChallengeResponse c, TestPki.Leaf leaf, String algorithm) {
        return new VerifyRequest(c.registrationId().toString(), leaf.der(), List.of(pki.intermediateDer()),
                TestPki.sign(leaf.keys(), algorithm, payload(c)), algorithm);
    }

    private CertificateSessionResponse enroll(String invitationCode, TestPki.Leaf leaf) {
        return service.verifyEnrollment(proof(challenge(invitationCode), leaf, "SHA256withRSA"));
    }

    private DoctorPrincipal principal(CertificateSessionResponse session) {
        return doctors.authenticate("Bearer " + session.activationToken());
    }

    private EnrollmentResponse approve(UUID doctorId, CertificateSessionResponse session) {
        return service.review(ADMIN, doctorId, new ReviewRequest(session.enrollment().registrationId().toString(),
                "APPROVE", session.enrollment().fingerprintSha256(), "evidence://license-scan", "checked"));
    }

    private CertificateSessionResponse login(TestPki.Leaf leaf) {
        ChallengeResponse c = service.createLoginChallenge("10.0.0.2");
        return service.login(new LoginRequest(c.challengeId().toString(), leaf.fingerprint(), leaf.der(),
                List.of(pki.intermediateDer()), TestPki.sign(leaf.keys(), "SHA256withRSA", payload(c)),
                "SHA256withRSA"));
    }

    /** Doctor with an ACTIVE enrollment and an activated signing key; returns [principal session]. */
    private CertificateSessionResponse activeDoctor(String email, TestPki.Leaf leaf) {
        PreregistrationResponse pre = preregister(email);
        CertificateSessionResponse session = enroll(pre.invitationCode(), leaf);
        DoctorPrincipal p = principal(session);
        service.consent(p, session.enrollment().registrationId().toString(), CONSENT);
        approve(pre.doctor().id(), session);
        doctors.activate(p);
        return session;
    }

    private TestPki.Leaf rsaLeaf() {
        return pki.leaf(TestPki.rsa(2048));
    }

    private static String code(Throwable t) {
        return ((ApiException) t).code();
    }

    private static HttpStatus status(Throwable t) {
        return ((ApiException) t).status();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void enrollmentRequiresProofReviewAndConsentBeforeActivationAndLogin() {
        PreregistrationResponse pre = preregister("kim@example.com");
        UUID doctorId = pre.doctor().id();
        ChallengeResponse c = challenge(pre.invitationCode());
        assertThat(c.purpose()).isEqualTo("CERTIFICATE_ENROLLMENT");
        assertThat(c.registrationId()).isEqualTo(c.challengeId());
        assertThat(c.doctorId()).isEqualTo(doctorId);
        assertThat(c.signedPayload()).startsWith("vision-certi-certificate-auth-v1\npurpose=CERTIFICATE_ENROLLMENT\n")
                .contains("doctorId=" + doctorId).contains("tenantId=tenant-a").contains("serviceId=svc-test")
                .contains("nonce=" + c.nonce());
        assertThat(new String(payload(c), StandardCharsets.UTF_8)).isEqualTo(c.signedPayload());

        TestPki.Leaf leaf = rsaLeaf();
        CertificateSessionResponse session = service.verifyEnrollment(proof(c, leaf, "SHA256withRSA"));
        EnrollmentResponse enrollment = session.enrollment();
        assertThat(enrollment.kind()).isEqualTo("PC_CERTIFICATE");
        assertThat(enrollment.registrationStatus()).isEqualTo("PENDING");
        assertThat(enrollment.externalStatus()).isEqualTo("GOOD");
        assertThat(enrollment.reviewStatus()).isEqualTo("PENDING");
        assertThat(enrollment.fingerprintSha256()).isEqualTo(leaf.fingerprint());
        assertThat(enrollment.keyAlgorithm()).isEqualTo("RSA-2048");
        assertThat(session.identityMode()).isEqualTo("PC_CERTIFICATE");
        assertThat(session.doctor().status()).isEqualTo(DoctorStatus.IDENTITY_VERIFIED);

        DoctorPrincipal p = principal(session);
        // Neither the proof alone nor consent alone activates anything.
        assertThatThrownBy(() -> doctors.activate(p)).satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_DOCTOR_STATE"));
        EnrollmentResponse consented = service.consent(p, enrollment.registrationId().toString(), CONSENT);
        assertThat(consented.registrationStatus()).isEqualTo("PENDING");
        assertThat(doctors.me(p).doctor().status()).isEqualTo(DoctorStatus.CONSENTED);
        assertThatThrownBy(() -> doctors.activate(p))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ENROLLMENT_REQUIRED"));
        assertThatThrownBy(() -> login(leaf)).satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_ENROLLED"));

        // The admin must repeat the exact fingerprint compared with evidence.
        assertThatThrownBy(() -> service.review(ADMIN, doctorId, new ReviewRequest(
                enrollment.registrationId().toString(), "APPROVE", "0".repeat(64), "evidence://x", null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("FINGERPRINT_MISMATCH"));
        EnrollmentResponse active = approve(doctorId, session);
        assertThat(active.registrationStatus()).isEqualTo("ACTIVE");
        assertThat(active.reviewMethod()).isEqualTo("ADMIN_REVIEW");
        assertThat(active.reviewedBy()).isEqualTo("admin-1");

        assertThat(doctors.activate(p).status()).isEqualTo(DoctorStatus.ACTIVE);
        assertThat(doctors.isActiveSigner(TENANT, doctorId.toString())).isTrue();

        CertificateSessionResponse again = login(leaf);
        assertThat(again.doctor().id()).isEqualTo(doctorId);
        assertThat(principal(again).identityMode()).isEqualTo("PC_CERTIFICATE");

        for (AuditEvent event : audit.list(TENANT)) {
            assertThat(event.details().toString()).doesNotContain(session.activationToken())
                    .doesNotContain(pre.invitationCode());
        }
    }

    @Test
    void ecdsaP256IsAcceptedAndAlgorithmPolicyIsEnforced() {
        TestPki.Leaf ec = pki.leaf(TestPki.ec("secp256r1"));
        PreregistrationResponse pre = preregister("ec@example.com");
        ChallengeResponse c1 = challenge(pre.invitationCode());
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(c1.registrationId().toString(), ec.der(),
                List.of(pki.intermediateDer()), TestPki.sign(ec.keys(), "SHA256withECDSA", payload(c1)),
                "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("ALGORITHM_KEY_MISMATCH"));
        ChallengeResponse c2 = challenge(pre.invitationCode());
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(c2.registrationId().toString(), ec.der(),
                List.of(pki.intermediateDer()), TestPki.sign(ec.keys(), "SHA1withECDSA", payload(c2)),
                "SHA1withECDSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGNATURE_ALGORITHM_NOT_ALLOWED"));
        ChallengeResponse c3 = challenge(pre.invitationCode());
        assertThat(service.verifyEnrollment(proof(c3, ec, "SHA256withECDSA")).enrollment().keyAlgorithm())
                .isEqualTo("EC-P256");
    }

    @Test
    void weakOrMisusedCertificatesAreRejected() {
        Instant now = clock.instant();
        List<TestPki.Leaf> bad = List.of(
                pki.leaf(TestPki.rsa(1024)),
                pki.leaf(TestPki.ec("secp384r1")),
                pki.leaf(TestPki.rsa(2048), pki.intermediate, KeyUsage.keyEncipherment,
                        now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30))));
        int i = 0;
        for (TestPki.Leaf leaf : bad) {
            PreregistrationResponse pre = preregister("bad" + i++ + "@example.com");
            ChallengeResponse c = challenge(pre.invitationCode());
            String algorithm = leaf.keys().getPublic().getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
            assertThatThrownBy(() -> service.verifyEnrollment(proof(c, leaf, algorithm)))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_INVALID"));
        }
        // A CA certificate is never an end-entity credential.
        PreregistrationResponse pre = preregister("ca@example.com");
        ChallengeResponse c = challenge(pre.invitationCode());
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(c.registrationId().toString(),
                pki.intermediateDer(), List.of(),
                TestPki.sign(pki.intermediate.keys(), "SHA256withRSA", payload(c)), "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_INVALID"));
    }

    @Test
    void revokedUnknownStaleAndForgedCrlsFailClosed() {
        TestPki.Leaf revoked = rsaLeaf();
        trust.crls.clear();
        trust.crls.addAll(pki.freshCrls(revoked.certificate()));
        PreregistrationResponse a = preregister("rev@example.com");
        assertThatThrownBy(() -> enroll(a.invitationCode(), revoked)).satisfies(t -> {
            assertThat(code(t)).isEqualTo("CERTIFICATE_REVOKED");
            assertThat(status(t)).isEqualTo(HttpStatus.FORBIDDEN);
        });

        // Revoked intermediate also revokes the leaf.
        trust.crls.clear();
        trust.crls.addAll(pki.freshCrls(pki.intermediate.certificate()));
        PreregistrationResponse b = preregister("revint@example.com");
        assertThatThrownBy(() -> enroll(b.invitationCode(), rsaLeaf()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_REVOKED"));

        // No CRL for the issuing CA → UNKNOWN.
        trust.crls.clear();
        trust.crls.add(pki.freshCrls().getFirst());
        PreregistrationResponse c = preregister("unknown@example.com");
        assertThatThrownBy(() -> enroll(c.invitationCode(), rsaLeaf())).satisfies(t -> {
            assertThat(code(t)).isEqualTo("CERTIFICATE_REVOCATION_UNAVAILABLE");
            assertThat(status(t)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(t.getMessage()).contains("UNKNOWN");
        });

        // Stale CRL (nextUpdate passed).
        Instant now = clock.instant();
        trust.crls.clear();
        trust.crls.add(pki.freshCrls().getFirst());
        trust.crls.add(pki.crl(pki.intermediate, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1))));
        PreregistrationResponse d = preregister("stale@example.com");
        assertThatThrownBy(() -> enroll(d.invitationCode(), rsaLeaf()))
                .satisfies(t -> assertThat(t.getMessage()).contains("STALE"));

        // A CRL with the right issuer name but signed by another key is ignored → UNKNOWN.
        trust.crls.clear();
        trust.crls.add(pki.freshCrls().getFirst());
        trust.crls.add(pki.crlSignedBy(pki.intermediate.name(), TestPki.rsa(2048).getPrivate(), now.minusSeconds(60),
                now.plus(Duration.ofHours(1))));
        PreregistrationResponse e = preregister("forged@example.com");
        assertThatThrownBy(() -> enroll(e.invitationCode(), rsaLeaf()))
                .satisfies(t -> assertThat(t.getMessage()).contains("UNKNOWN"));
    }

    @Test
    void untrustedOrExpiredCertificatesAreRejectedAndClientCannotSupplyAnchors() {
        TestPki other = new TestPki(clock, "Rogue");
        TestPki.Leaf rogue = other.leaf(TestPki.rsa(2048));
        PreregistrationResponse pre = preregister("rogue@example.com");
        ChallengeResponse c = challenge(pre.invitationCode());
        // Even with the rogue root and intermediate in the client chain, there is no path to a configured anchor.
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(c.registrationId().toString(), rogue.der(),
                List.of(other.intermediateDer(), Base64.getEncoder().encodeToString(encoded(other.root.certificate()))),
                TestPki.sign(rogue.keys(), "SHA256withRSA", payload(c)), "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_UNTRUSTED"));

        Instant now = clock.instant();
        TestPki.Leaf expired = pki.leaf(TestPki.rsa(2048), pki.intermediate, KeyUsage.digitalSignature,
                now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(1)));
        PreregistrationResponse pre2 = preregister("expired@example.com");
        assertThatThrownBy(() -> enroll(pre2.invitationCode(), expired))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_UNTRUSTED"));

        trust.anchors.clear();
        PreregistrationResponse pre3 = preregister("none@example.com");
        assertThatThrownBy(() -> challenge(pre3.invitationCode()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_TRUST_NOT_CONFIGURED"));
    }

    @Test
    void challengesAreSingleUsePurposeBoundExpiringAndNotTransplantable() {
        TestPki.Leaf leaf = rsaLeaf();
        PreregistrationResponse a = preregister("a@example.com");
        PreregistrationResponse b = preregister("b@example.com");
        ChallengeResponse forA = challenge(a.invitationCode());
        ChallengeResponse forB = challenge(b.invitationCode());

        // A signature over doctor A's bytes does not verify against doctor B's stored bytes.
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(forB.registrationId().toString(),
                leaf.der(), List.of(pki.intermediateDer()), TestPki.sign(leaf.keys(), "SHA256withRSA", payload(forA)),
                "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_SIGNATURE_INVALID"));
        // B's challenge is consumed by that attempt.
        assertThatThrownBy(() -> service.verifyEnrollment(proof(forB, leaf, "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_ALREADY_USED"));

        service.verifyEnrollment(proof(forA, leaf, "SHA256withRSA"));
        assertThatThrownBy(() -> service.verifyEnrollment(proof(forA, leaf, "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_ALREADY_USED"));

        // The same certificate cannot also be bound to doctor B.
        PreregistrationResponse b2 = doctors.reissueInvitation(ADMIN, b.doctor().id());
        assertThatThrownBy(() -> enroll(b2.invitationCode(), leaf))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ALREADY_BOUND"));

        ChallengeResponse login = service.createLoginChallenge("s");
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(login.challengeId().toString(),
                leaf.der(), List.of(), "AA==", "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_PURPOSE_MISMATCH"));

        PreregistrationResponse c = preregister("c@example.com");
        ChallengeResponse late = challenge(c.invitationCode());
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> service.verifyEnrollment(proof(late, rsaLeaf(), "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_EXPIRED"));
        assertThatThrownBy(() -> challenge("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
    }

    @Test
    void failedProofsLockTheInvitation() {
        PreregistrationResponse pre = preregister("lock@example.com");
        TestPki.Leaf leaf = rsaLeaf();
        for (int i = 0; i < 4; i++) {
            ChallengeResponse c = challenge(pre.invitationCode());
            assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(c.registrationId().toString(),
                    leaf.der(), List.of(pki.intermediateDer()), TestPki.sign(leaf.keys(), "SHA256withRSA",
                            "wrong".getBytes(StandardCharsets.UTF_8)), "SHA256withRSA")))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_SIGNATURE_INVALID"));
        }
        ChallengeResponse c = challenge(pre.invitationCode());
        assertThatThrownBy(() -> service.verifyEnrollment(new VerifyRequest(c.registrationId().toString(),
                leaf.der(), List.of(pki.intermediateDer()), "AAAA", "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_LOCKED"));
        assertThatThrownBy(() -> challenge(pre.invitationCode()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
    }

    @Test
    void adminReviewRulesAndAdminCannotActForTheDoctor() {
        PreregistrationResponse pre = preregister("rv@example.com");
        UUID doctorId = pre.doctor().id();
        CertificateSessionResponse session = enroll(pre.invitationCode(), rsaLeaf());
        String rid = session.enrollment().registrationId().toString();
        String fp = session.enrollment().fingerprintSha256();

        assertThatThrownBy(() -> service.review(DOCTOR_ROLE, doctorId, new ReviewRequest(rid, "APPROVE", fp, "e", null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("ADMIN_ROLE_REQUIRED"));
        assertThatThrownBy(() -> service.review(OTHER_ADMIN, doctorId, new ReviewRequest(rid, "APPROVE", fp, "e", null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_FOUND"));
        assertThatThrownBy(() -> service.review(ADMIN, doctorId, new ReviewRequest(rid, "APPROVE", fp, " ", null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("VALIDATION_FAILED"));

        // Approval before consent does not activate; only the doctor's own consent completes it.
        EnrollmentResponse approved = approve(doctorId, session);
        assertThat(approved.reviewStatus()).isEqualTo("APPROVED");
        assertThat(approved.registrationStatus()).isEqualTo("PENDING");
        assertThat(service.activeEnrollmentId(doctorId)).isNull();
        assertThat(service.consent(principal(session), rid, CONSENT).registrationStatus()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> approve(doctorId, session))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_ENROLLMENT_STATE"));

        // Rejection revokes the candidate and keeps it in history.
        PreregistrationResponse pre2 = preregister("rj@example.com");
        CertificateSessionResponse s2 = enroll(pre2.invitationCode(), rsaLeaf());
        EnrollmentResponse rejected = service.review(ADMIN, pre2.doctor().id(), new ReviewRequest(
                s2.enrollment().registrationId().toString(), "REJECT", null, null, "photo mismatch"));
        assertThat(rejected.reviewStatus()).isEqualTo("REJECTED");
        assertThat(rejected.registrationStatus()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.consent(principal(s2), s2.enrollment().registrationId().toString(), CONSENT))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_ENROLLMENT_STATE"));
        assertThat(service.listForAdmin(ADMIN, pre2.doctor().id())).hasSize(1);
    }

    @Test
    void preverifiedExpectedFingerprintCountsAsReview() {
        TestPki.Leaf leaf = rsaLeaf();
        PreregistrationResponse pre = preregister("exp@example.com", leaf.fingerprint().toUpperCase());
        CertificateSessionResponse session = enroll(pre.invitationCode(), leaf);
        assertThat(session.enrollment().reviewStatus()).isEqualTo("APPROVED");
        assertThat(session.enrollment().reviewMethod()).isEqualTo("EXPECTED_FINGERPRINT");
        assertThat(service.consent(principal(session), session.enrollment().registrationId().toString(), CONSENT)
                .registrationStatus()).isEqualTo("ACTIVE");

        // A different certificate does not match the expectation and needs a manual review.
        PreregistrationResponse other = preregister("exp2@example.com", leaf.fingerprint());
        TestPki.Leaf different = rsaLeaf();
        assertThat(enroll(other.invitationCode(), different).enrollment().reviewStatus()).isEqualTo("PENDING");
    }

    @Test
    void googleSessionCannotActivateEvenWithActiveCertificate() {
        PreregistrationResponse pre = preregister("g@example.com");
        var googleChallenge = doctors.createActivationChallenge(pre.invitationCode(), "s");
        DoctorPrincipal googleSession = doctors.authenticate("Bearer " + doctors.completeGoogle(
                googleChallenge.challengeId(), google.token("sub-g", "g@example.com", googleChallenge.nonce()))
                .activationToken());
        // Certificate enrollment started from the Google session (bearer renewal path).
        ChallengeResponse c = service.createEnrollmentChallenge(null, googleSession, "s");
        TestPki.Leaf leaf = rsaLeaf();
        CertificateSessionResponse certSession = service.verifyEnrollment(proof(c, leaf, "SHA256withRSA"));
        service.consent(principal(certSession), certSession.enrollment().registrationId().toString(), CONSENT);
        approve(pre.doctor().id(), certSession);

        assertThatThrownBy(() -> doctors.activate(googleSession))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ENROLLMENT_REQUIRED"));
        assertThat(doctors.activate(principal(certSession)).status()).isEqualTo(DoctorStatus.ACTIVE);
    }

    @Test
    void renewalSupersedesAndUnregisterInvalidatesSessionsAndSigning() {
        TestPki.Leaf first = rsaLeaf();
        CertificateSessionResponse s1 = activeDoctor("rn@example.com", first);
        UUID doctorId = s1.doctor().id();
        DoctorPrincipal p1 = principal(s1);

        // Renewal: fresh proof with the new certificate, consent and review again.
        ChallengeResponse renewal = service.createEnrollmentChallenge(null, p1, "s");
        TestPki.Leaf second = rsaLeaf();
        CertificateSessionResponse s2 = service.verifyEnrollment(proof(renewal, second, "SHA256withRSA"));
        assertThat(s2.enrollment().registrationStatus()).isEqualTo("PENDING");
        assertThat(service.activeEnrollmentId(doctorId)).isEqualTo(s1.enrollment().registrationId());
        service.consent(principal(s2), s2.enrollment().registrationId().toString(), CONSENT);
        EnrollmentResponse renewed = approve(doctorId, s2);
        assertThat(renewed.registrationStatus()).isEqualTo("ACTIVE");
        assertThat(renewed.supersedes()).isEqualTo(s1.enrollment().registrationId());
        List<EnrollmentResponse> history = service.listForDoctor(principal(s2));
        assertThat(history).hasSize(2);
        assertThat(history.get(1).registrationStatus()).isEqualTo("REVOKED");
        assertThat(history.get(1).revocationReason()).isEqualTo("SUPERSEDED");
        assertThat(history.get(1).fingerprintSha256()).isEqualTo(first.fingerprint());
        assertThatThrownBy(() -> login(first)).satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_ENROLLED"));
        CertificateSessionResponse s3 = login(second);

        // Unregister: enrollment revoked, every session dropped, signing blocked.
        service.unregister(principal(s3), renewed.registrationId().toString(), "lost token");
        for (CertificateSessionResponse s : List.of(s1, s2, s3)) {
            assertThatThrownBy(() -> doctors.authenticate("Bearer " + s.activationToken()))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_SESSION_REQUIRED"));
        }
        assertThat(doctors.isActiveSigner(TENANT, doctorId.toString())).isFalse();
        assertThatThrownBy(() -> doctors.signerFor(p1).assertCanSign())
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ENROLLMENT_REQUIRED"));
        assertThatThrownBy(() -> login(second)).satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_ENROLLED"));

        // Re-enrollment needs a reissued invitation, a new proof, consent and review.
        PreregistrationResponse again = doctors.reissueInvitation(ADMIN, doctorId);
        TestPki.Leaf third = rsaLeaf();
        CertificateSessionResponse s4 = enroll(again.invitationCode(), third);
        service.consent(principal(s4), s4.enrollment().registrationId().toString(), CONSENT);
        approve(doctorId, s4);
        assertThat(doctors.isActiveSigner(TENANT, doctorId.toString())).isTrue();
        assertThat(service.listForAdmin(ADMIN, doctorId)).hasSize(3);
    }

    @Test
    void fingerprintIsTheFullDerCertificateSoReissueNeedsReEnrollment() throws Exception {
        TestPki.Leaf leaf = rsaLeaf();
        assertThat(leaf.fingerprint()).isEqualTo(com.inviz.visioncerti.common.Hashing.sha256Hex(
                leaf.certificate().getEncoded()));
        CertificateSessionResponse s = activeDoctor("ri@example.com", leaf);
        assertThat(s.enrollment().publicKeySha256()).isEqualTo(com.inviz.visioncerti.common.Hashing.sha256Hex(
                leaf.certificate().getPublicKey().getEncoded()));

        // Same key pair, new serial/expiry: a different certificate that is not silently accepted.
        TestPki.Leaf reissued = pki.leaf(leaf.keys());
        assertThat(reissued.fingerprint()).isNotEqualTo(leaf.fingerprint());
        assertThatThrownBy(() -> login(reissued))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_ENROLLED"));

        // Explicit re-enrollment of the reissued certificate is a new PENDING record needing consent and review.
        ChallengeResponse renewal = service.createEnrollmentChallenge(null, principal(s), "s");
        CertificateSessionResponse s2 = service.verifyEnrollment(proof(renewal, reissued, "SHA256withRSA"));
        assertThat(s2.enrollment().registrationStatus()).isEqualTo("PENDING");
        assertThat(s2.enrollment().publicKeySha256()).isEqualTo(s.enrollment().publicKeySha256());

        // The same key cannot be bound to another doctor through a reissued certificate either.
        PreregistrationResponse other = preregister("ri2@example.com");
        TestPki.Leaf third = pki.leaf(leaf.keys());
        assertThatThrownBy(() -> enroll(other.invitationCode(), third))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ALREADY_BOUND"));
    }

    /** assertCanSign → remote signDigest (during which {@code change} runs) → confirmStillAuthorized. */
    private Throwable signAcross(DoctorPrincipal p, Runnable change) {
        RegisteredDoctorSigner signer = doctors.signerFor(p);
        RegisteredDoctorSigner.SignerSnapshot snapshot = signer.assertCanSign();
        duringRemoteSign = change;
        try {
            signer.signDigest(snapshot, new byte[32]);
        } finally {
            duringRemoteSign = () -> { };
        }
        return org.assertj.core.api.Assertions.catchThrowable(() -> signer.confirmStillAuthorized(snapshot));
    }

    @Test
    void signingRechecksTheSameEnrollmentAfterTheRemoteOperation() {
        TestPki.Leaf first = rsaLeaf();
        CertificateSessionResponse s1 = activeDoctor("race@example.com", first);
        UUID doctorId = s1.doctor().id();
        DoctorPrincipal p1 = principal(s1);

        // Baseline: nothing changes → confirmed, and the snapshot names the enrollment that authorised it.
        RegisteredDoctorSigner.SignerSnapshot ok = doctors.signerFor(p1).assertCanSign();
        assertThat(ok.certificateEnrollmentId()).isEqualTo(s1.enrollment().registrationId().toString());
        assertThat(ok.certificateEnrollmentFingerprint()).isEqualTo(first.fingerprint());
        assertThat(signAcross(p1, () -> { })).isNull();

        // Renewal completes while the backend signs: doctor still ACTIVE with *an* ACTIVE enrollment, but not the
        // one that was validated → the signature is discarded.
        ChallengeResponse renewal = service.createEnrollmentChallenge(null, p1, "s");
        TestPki.Leaf second = rsaLeaf();
        CertificateSessionResponse s2 = service.verifyEnrollment(proof(renewal, second, "SHA256withRSA"));
        service.consent(principal(s2), s2.enrollment().registrationId().toString(), CONSENT);
        Throwable renewed = signAcross(p1, () -> approve(doctorId, s2));
        assertThat(code(renewed)).isEqualTo("CERTIFICATE_ENROLLMENT_CHANGED");
        assertThat(doctors.isActiveSigner(TENANT, doctorId.toString())).isTrue();
        // The old certificate's session is no longer bound to the ACTIVE enrollment; a login with the new one is.
        assertThatThrownBy(() -> doctors.signerFor(p1).assertCanSign())
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ENROLLMENT_CHANGED"));
        DoctorPrincipal p3 = principal(login(second));
        assertThat(signAcross(p3, () -> { })).isNull();

        // External revocation observed by another request while signing.
        Throwable revoked = signAcross(p3, () -> {
            trust.crls.clear();
            trust.crls.addAll(pki.freshCrls(second.certificate()));
            assertThatThrownBy(() -> login(second)).isInstanceOf(ApiException.class);
        });
        assertThat(code(revoked)).isEqualTo("CERTIFICATE_ENROLLMENT_CHANGED");
    }

    @Test
    void unregisterDuringRemoteSigningDiscardsTheSignature() {
        TestPki.Leaf leaf = rsaLeaf();
        CertificateSessionResponse s = activeDoctor("race2@example.com", leaf);
        DoctorPrincipal p = principal(s);
        Throwable t = signAcross(p, () -> service.unregister(p, s.enrollment().registrationId().toString(), "lost"));
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(code(t)).isIn("CERTIFICATE_ENROLLMENT_CHANGED", "DOCTOR_SESSION_REQUIRED");
    }

    @Test
    void loginRechecksFingerprintRevocationAndDoctorStatus() {
        TestPki.Leaf leaf = rsaLeaf();
        CertificateSessionResponse s = activeDoctor("lg@example.com", leaf);
        ChallengeResponse c = service.createLoginChallenge("s");
        assertThatThrownBy(() -> service.login(new LoginRequest(c.challengeId().toString(), "ab".repeat(32),
                leaf.der(), List.of(pki.intermediateDer()), TestPki.sign(leaf.keys(), "SHA256withRSA", payload(c)),
                "SHA256withRSA")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("FINGERPRINT_MISMATCH"));
        assertThatThrownBy(() -> login(rsaLeaf()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_ENROLLED"));

        // Signing re-validates against the current CRL: stale → refused.
        Instant now = clock.instant();
        trust.crls.clear();
        trust.crls.add(pki.freshCrls().getFirst());
        trust.crls.add(pki.crl(pki.intermediate, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1))));
        assertThatThrownBy(() -> doctors.signerFor(principal(s)).assertCanSign())
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_ENROLLMENT_NOT_VALID"));

        // Revoked by the CA → login refused and the enrollment is revoked internally.
        trust.crls.clear();
        trust.crls.addAll(pki.freshCrls(leaf.certificate()));
        assertThatThrownBy(() -> login(leaf)).satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_REVOKED"));
        EnrollmentResponse after = service.listForAdmin(ADMIN, s.doctor().id()).getFirst();
        assertThat(after.registrationStatus()).isEqualTo("REVOKED");
        assertThat(after.externalStatus()).isEqualTo("REVOKED");
        assertThat(after.revocationReason()).isEqualTo("EXTERNAL_REVOKED");

        // Suspended doctor cannot log in with a still-valid certificate.
        trust.crls.clear();
        trust.crls.addAll(pki.freshCrls());
        TestPki.Leaf other = rsaLeaf();
        CertificateSessionResponse s2 = activeDoctor("lg2@example.com", other);
        doctors.changeStatus(ADMIN, s2.doctor().id(), new ChangeDoctorStatus("SUSPENDED", "조사"));
        assertThatThrownBy(() -> login(other)).satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_ALLOWED"));
    }

    @Test
    void mobileCallbacksMustBeAuthenticatedAndConsumedOnce() {
        PreregistrationResponse pre = preregister("mb@example.com");
        MobileStartResponse start = service.startMobile("CERTIFICATE_ENROLLMENT", pre.invitationCode(), "s");
        assertThat(start.provider()).isEqualTo(FakeMobileProvider.NAME);
        MobileIdentityProvider.Transaction t = fakeMobile.started.get(start.transactionId());
        String id = start.transactionId().toString();

        byte[] unsigned = fakeMobile.body(t, "subject-1", t.nonce(), clock.instant());
        assertThatThrownBy(() -> service.mobileCallback(id, Map.of(), unsigned))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_CALLBACK_REJECTED"));
        assertThatThrownBy(() -> service.mobileCallback(id, Map.of("x-fake-signature", "00"), unsigned))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_CALLBACK_REJECTED"));
        byte[] wrongNonce = fakeMobile.body(t, "subject-1", "other-nonce", clock.instant());
        assertThatThrownBy(() -> service.mobileCallback(id, Map.of("x-fake-signature", fakeMobile.hmac(wrongNonce)),
                wrongNonce))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_CALLBACK_REJECTED"));
        assertThatThrownBy(() -> service.consumeMobile(id, start.transactionSecret()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_TRANSACTION_NOT_VERIFIED"));

        assertThat(service.mobileCallback(id, Map.of("x-fake-signature", fakeMobile.hmac(unsigned)), unsigned)
                .status()).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> service.mobileCallback(id, Map.of("x-fake-signature", fakeMobile.hmac(unsigned)),
                unsigned))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_CALLBACK_REJECTED"));
        assertThatThrownBy(() -> service.consumeMobile(id, "wrong-secret"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_TRANSACTION_NOT_FOUND"));
        CertificateSessionResponse session = service.consumeMobile(id, start.transactionSecret());
        assertThat(session.identityMode()).isEqualTo("MOBILE_CERTIFICATE");
        assertThat(session.enrollment().kind()).isEqualTo("MOBILE_CERTIFICATE");
        assertThat(session.enrollment().registrationStatus()).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.consumeMobile(id, start.transactionSecret()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MOBILE_TRANSACTION_ALREADY_USED"));

        service.consent(principal(session), session.enrollment().registrationId().toString(), CONSENT);
        approve(pre.doctor().id(), session);
        MobileStartResponse loginStart = service.startMobile("CERTIFICATE_LOGIN", null, "s");
        MobileIdentityProvider.Transaction lt = fakeMobile.started.get(loginStart.transactionId());
        byte[] loginBody = fakeMobile.body(lt, "subject-1", lt.nonce(), clock.instant());
        service.mobileCallback(loginStart.transactionId().toString(),
                Map.of("x-fake-signature", fakeMobile.hmac(loginBody)), loginBody);
        assertThat(service.consumeMobile(loginStart.transactionId().toString(), loginStart.transactionSecret())
                .doctor().id()).isEqualTo(pre.doctor().id());
    }

    @Test
    void mobileWithoutAdapterIsUnavailable() {
        CertificateEnrollmentService noMobile = new CertificateEnrollmentService(doctors,
                new CertificateValidator(trust, clock), new MobileIdentityProvider.NotConfigured("some-vendor"), null,
                audit, clock, "svc", Duration.ofMinutes(5));
        assertThat(noMobile.config().mobile().configured()).isFalse();
        assertThatThrownBy(() -> noMobile.startMobile("CERTIFICATE_LOGIN", null, "s"))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("MOBILE_PROVIDER_NOT_CONFIGURED");
                    assertThat(status(t)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThatThrownBy(() -> noMobile.mobileCallback(UUID.randomUUID().toString(), Map.of(),
                "{\"result\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("MOBILE_PROVIDER_NOT_CONFIGURED"));
    }

    @Test
    void fileTrustSourceReadsAnchorsAndCrlsFromDisk(@TempDir Path dir) throws Exception {
        Path anchors = dir.resolve("anchors.pem");
        Files.writeString(anchors, TestPki.pem(pki.root.certificate()));
        Path rootCrl = dir.resolve("root.crl");
        Path issuingCrl = dir.resolve("issuing.crl");
        List<X509CRL> crls = pki.freshCrls();
        Files.write(rootCrl, crls.get(0).getEncoded());
        Files.write(issuingCrl, crls.get(1).getEncoded());
        FileCertificateTrustSource files = new FileCertificateTrustSource(anchors.toString(),
                rootCrl + "," + issuingCrl + "," + dir.resolve("missing.crl"));
        assertThat(files.trustAnchors()).hasSize(1);
        assertThat(files.crls()).hasSize(2);
        CertificateValidator validator = new CertificateValidator(files, clock);
        TestPki.Leaf leaf = rsaLeaf();
        assertThat(validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation())
                .isEqualTo(CertificateValidator.RevocationStatus.GOOD);
        assertThat(new FileCertificateTrustSource("", "").trustAnchors()).isEmpty();
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
