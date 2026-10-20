package com.inviz.visioncerti.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.LoginRequest;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorPolicy;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.credential.DevSimulatedCredentialProvider;
import com.inviz.visioncerti.doctor.identity.TestGoogleTokens;
import com.inviz.visioncerti.integration.IntegrationClient;
import com.inviz.visioncerti.integration.IntegrationClients;
import com.inviz.visioncerti.integration.PacsCertificateProofService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PacsCertificateProofServiceTest {
    private MutableClock clock;
    private TestPki pki;
    private CertificateEnrollmentServiceTest.TestTrust trust;
    private PacsCertificateProofService service;
    private IntegrationClient pacs;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T00:00:00Z"));
        AuditService audit = new AuditService(clock);
        DoctorService doctors = new DoctorService(audit, new DevSimulatedCredentialProvider(clock),
                new TestGoogleTokens(clock).verifier(), clock, DoctorPolicy.defaults(), false, true, true);
        pki = new TestPki(clock, "PACS Proof");
        trust = new CertificateEnrollmentServiceTest.TestTrust();
        trust.anchors.add(pki.root.certificate());
        trust.crls.addAll(pki.freshCrls());
        pacs = new IntegrationClient("pacs-sv70", "secret-not-used-in-unit-test".getBytes(StandardCharsets.UTF_8),
                "vision-certi:pacs-integration:v1", Set.of("hospital-a"));
        service = new PacsCertificateProofService(IntegrationClients.of(pacs),
                new CertificateValidator(trust, clock), doctors, audit, clock, "svc-test");
    }

    private LoginRequest signed(ChallengeResponse challenge, TestPki.Leaf leaf) {
        return new LoginRequest(challenge.challengeId().toString(), leaf.fingerprint(), leaf.der(),
                java.util.List.of(pki.intermediateDer()),
                TestPki.sign(leaf.keys(), "SHA256withRSA", challenge.signedPayload().getBytes(StandardCharsets.UTF_8)),
                "SHA256withRSA");
    }

    @Test
    void unregisteredValidCertificateReturnsOneTimeReceiptWithoutPacsCredentials() {
        ChallengeResponse challenge = service.start(new PacsCertificateProofService.StartRequest("pacs-sv70"), "127.0.0.1");
        TestPki.Leaf leaf = pki.leaf(TestPki.rsa(2048));
        assertThat(challenge.signedPayload()).contains("audience=pacs-sv70");
        var code = service.verify(signed(challenge, leaf));
        var receipt = service.redeem(pacs, new PacsCertificateProofService.RedeemRequest(code.verificationCode()));
        assertThat(receipt.verified()).isTrue();
        assertThat(receipt.authentication()).isEqualTo("Y");
        assertThat(receipt.certificateFingerprintSha256()).isEqualTo(leaf.fingerprint());
        assertThat(receipt.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(2)));
        assertThat(service.status(pacs, receipt.verificationId()).verified()).isTrue();
        assertThatThrownBy(() -> service.redeem(pacs,
                new PacsCertificateProofService.RedeemRequest(code.verificationCode())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.verify(signed(challenge, leaf))).isInstanceOf(ApiException.class);
        clock.advance(Duration.ofHours(2));
        assertThatThrownBy(() -> service.status(pacs, receipt.verificationId())).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidSignatureCannotProduceVerifiedJson() {
        ChallengeResponse challenge = service.start(new PacsCertificateProofService.StartRequest("pacs-sv70"), "127.0.0.2");
        TestPki.Leaf leaf = pki.leaf(TestPki.rsa(2048));
        TestPki.Leaf other = pki.leaf(TestPki.rsa(2048));
        LoginRequest forged = new LoginRequest(challenge.challengeId().toString(), leaf.fingerprint(), leaf.der(),
                java.util.List.of(pki.intermediateDer()),
                TestPki.sign(other.keys(), "SHA256withRSA", challenge.signedPayload().getBytes(StandardCharsets.UTF_8)),
                "SHA256withRSA");
        assertThatThrownBy(() -> service.verify(forged)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CERTIFICATE_SIGNATURE_INVALID"));
    }

    @Test
    void revokedCertificateInvalidatesPreviouslyRedeemedReceipt() {
        ChallengeResponse challenge = service.start(new PacsCertificateProofService.StartRequest("pacs-sv70"), "127.0.0.3");
        TestPki.Leaf leaf = pki.leaf(TestPki.rsa(2048));
        var code = service.verify(signed(challenge, leaf));
        var receipt = service.redeem(pacs, new PacsCertificateProofService.RedeemRequest(code.verificationCode()));
        trust.crls.clear();
        trust.crls.addAll(pki.freshCrls(leaf.certificate()));
        assertThatThrownBy(() -> service.status(pacs, receipt.verificationId())).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CERTIFICATE_REVOKED"));
    }
}
