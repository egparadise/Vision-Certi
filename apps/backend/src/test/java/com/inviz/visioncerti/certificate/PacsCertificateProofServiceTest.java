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
import com.inviz.visioncerti.integration.PacsProofActivityController;
import com.inviz.visioncerti.integration.PacsRequestContext;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.store.PostgresTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PacsCertificateProofServiceTest {
    private static final String PC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String PC_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String PC_NONCE = "a".repeat(64);
    private MutableClock clock;
    private TestPki pki;
    private CertificateEnrollmentServiceTest.TestTrust trust;
    private PacsCertificateProofService service;
    private IntegrationClient pacs;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T00:00:00Z"));
        audit = new AuditService(clock);
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

    private static final String ACCOUNT_KEY = "b".repeat(64);
    private static final String BOOT = "c".repeat(64);
    private final PacsCertificateProofService.ClientInfo owner = new PacsCertificateProofService.ClientInfo("reader", "42", "PC-A", "device-a");
    private final PacsCertificateProofService.ClientInfo other = new PacsCertificateProofService.ClientInfo("reader", "42", "PC-B", "device-b");

    private PacsCertificateProofService.AccountStatus activateLease() {
        var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId(), null,
                PC_NONCE, PC_CHALLENGE, "S256"), "127.0.0.1");
        var code = service.verify(signed(challenge, pki.leaf(TestPki.rsa(2048)))).verificationCode();
        var proof = service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(pacs.clientId(), code,
                PC_VERIFIER, PC_NONCE), PacsRequestContext.EMPTY).receipt();
        return service.activateAccount(new PacsCertificateProofService.AccountActivateRequest(pacs.clientId(),
                proof.verificationId(), PC_VERIFIER, ACCOUNT_KEY, BOOT, owner), PacsRequestContext.EMPTY);
    }

    private PacsCertificateProofService.AccountStatus sharedStatus(String key, PacsCertificateProofService.ClientInfo info) {
        return service.accountStatus(new PacsCertificateProofService.AccountStatusRequest(pacs.clientId(), key, info), PacsRequestContext.EMPTY);
    }

    @Test
    void accountLeaseSharesOnlyMatchingCredentialCapabilityAndAccount() {
        var state = activateLease();
        assertThat(state.expiresAt()).isEqualTo(state.verifiedAt().plus(Duration.ofHours(72)));
        assertThat(sharedStatus(ACCOUNT_KEY, other).active()).isTrue();
        assertThat(sharedStatus("d".repeat(64), other).active()).isFalse();
        assertThat(sharedStatus(ACCOUNT_KEY, new PacsCertificateProofService.ClientInfo("different", "42", "PC-B", "device-b")).active()).isFalse();
        assertThat(sharedStatus(ACCOUNT_KEY, new PacsCertificateProofService.ClientInfo("reader", "43", "PC-B", "device-b")).active()).isFalse();
        assertThat(audit.list("hospital-a").toString()).doesNotContain(ACCOUNT_KEY, PC_VERIFIER, BOOT);
    }

    @Test
    void originOfflineCannotBeRevivedByLateHeartbeatOrReactivation() {
        var state = activateLease();
        clock.advance(Duration.ofSeconds(90));
        assertThat(sharedStatus(ACCOUNT_KEY, other).reason()).isEqualTo("SOURCE_OFFLINE");
        assertThat(service.heartbeatAccount(new PacsCertificateProofService.AccountHeartbeatRequest(pacs.clientId(),
                state.receipt().verificationId(), PC_VERIFIER, BOOT, "device-a", false), PacsRequestContext.EMPTY).active()).isFalse();
        assertThatThrownBy(() -> service.activateAccount(new PacsCertificateProofService.AccountActivateRequest(pacs.clientId(),
                state.receipt().verificationId(), PC_VERIFIER, ACCOUNT_KEY, BOOT, owner), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
    }

    @Test
    void secondaryDeviceOrRestartCannotMaintainLeaseAndCloseEndsImmediately() {
        var state = activateLease();
        var id = state.receipt().verificationId();
        assertThatThrownBy(() -> service.heartbeatAccount(new PacsCertificateProofService.AccountHeartbeatRequest(pacs.clientId(),
                id, PC_VERIFIER, BOOT, "device-b", false), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.heartbeatAccount(new PacsCertificateProofService.AccountHeartbeatRequest(pacs.clientId(),
                id, PC_VERIFIER, "e".repeat(64), "device-a", false), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.heartbeatAccount(new PacsCertificateProofService.AccountHeartbeatRequest(pacs.clientId(),
                id, "wrong", BOOT, "device-a", false), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        service.heartbeatAccount(new PacsCertificateProofService.AccountHeartbeatRequest(pacs.clientId(), id,
                PC_VERIFIER, BOOT, "device-a", true), PacsRequestContext.EMPTY);
        assertThat(sharedStatus(ACCOUNT_KEY, other).active()).isFalse();
        assertThatThrownBy(() -> service.statusPc(new PacsCertificateProofService.PcStatusRequest(pacs.clientId(), id, PC_VERIFIER),
                PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
    }

    @Test
    void heartbeatsNeverExtendAbsolute72HoursAndRenewalRequiresFreshProof() {
        var state = activateLease();
        for (int minute = 1; minute <= 72 * 60; minute++) {
            clock.advance(Duration.ofMinutes(1));
            var pulse = service.heartbeatAccount(new PacsCertificateProofService.AccountHeartbeatRequest(pacs.clientId(),
                    state.receipt().verificationId(), PC_VERIFIER, BOOT, "device-a", false), PacsRequestContext.EMPTY);
            assertThat(pulse.expiresAt()).isEqualTo(state.expiresAt());
            assertThat(pulse.active()).isEqualTo(minute < 72 * 60);
        }
        assertThat(sharedStatus(ACCOUNT_KEY, other).reason()).isEqualTo("EXPIRED");
    }

    @Test
    void accountApprovalEventsAreAttributedAndDeduplicated() {
        activateLease();
        var account = new PacsCertificateProofService.AccountStatusRequest(pacs.clientId(), ACCOUNT_KEY, other);
        var event = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "APPROVAL_REQUESTED", other, "f".repeat(64));
        var request = new PacsCertificateProofService.AccountEventRequest(account, event);
        assertThat(service.accountEvent(request, PacsRequestContext.EMPTY).duplicate()).isFalse();
        assertThat(service.accountEvent(request, PacsRequestContext.EMPTY).duplicate()).isTrue();
        clock.advance(Duration.ofSeconds(90));
        assertThatThrownBy(() -> service.accountEvent(request, PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
    }

    @Test
    void pcProofRequiresOriginalVerifierNonceAndFreshCodeWithoutAnySharedKey() {
        var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId(), null,
                PC_NONCE, PC_CHALLENGE, "S256"), "127.0.0.1");
        assertThat(challenge.signedPayload()).contains("pcNonce=" + PC_NONCE, "codeChallenge=" + PC_CHALLENGE);
        var leaf = pki.leaf(TestPki.rsa(2048));
        var code = service.verify(signed(challenge, leaf)).verificationCode();
        var context = PacsRequestContext.EMPTY;
        assertThatThrownBy(() -> service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(
                pacs.clientId(), code, "x".repeat(43), PC_NONCE), context)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(
                pacs.clientId(), code, PC_VERIFIER, "b".repeat(64)), context)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(
                "other-pacs", code, PC_VERIFIER, PC_NONCE), context)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.redeem(pacs, new PacsCertificateProofService.RedeemRequest(code)))
                .isInstanceOf(ApiException.class);
        var request = new PacsCertificateProofService.PcRedeemRequest(pacs.clientId(), code, PC_VERIFIER, PC_NONCE);
        var delivered = service.redeemPc(request, context);
        assertThat(delivered.nonce()).isEqualTo(PC_NONCE);
        assertThat(delivered.codeChallenge()).isEqualTo(PC_CHALLENGE);
        assertThat(delivered.receipt().certificateFingerprintSha256()).isEqualTo(leaf.fingerprint());
        assertThatThrownBy(() -> service.redeemPc(request, context)).isInstanceOf(ApiException.class);
        var id = delivered.receipt().verificationId();
        var status = new PacsCertificateProofService.PcStatusRequest(pacs.clientId(), id, PC_VERIFIER);
        assertThat(service.statusPc(status, context).verified()).isTrue();
        assertThatThrownBy(() -> service.statusPc(new PacsCertificateProofService.PcStatusRequest(
                pacs.clientId(), id, "x".repeat(43)), context)).isInstanceOf(ApiException.class);
        assertThat(audit.list("hospital-a").toString()).doesNotContain(code, PC_VERIFIER);
        trust.crls.clear();
        trust.crls.addAll(pki.freshCrls(leaf.certificate()));
        assertThatThrownBy(() -> service.statusPc(status, context)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CERTIFICATE_REVOKED"));
    }

    @Test
    void pcExchangeRejectsDowngradesLegacyCodesAndExpiry() {
        for (String method : new String[]{null, "plain", "s256"}) {
            assertThatThrownBy(() -> service.start(new PacsCertificateProofService.StartRequest(pacs.clientId(), null,
                    PC_NONCE, PC_CHALLENGE, method), "127.0.0.1")).isInstanceOf(ApiException.class);
        }
        var leaf = pki.leaf(TestPki.rsa(2048));
        var legacy = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId()), "127.0.0.1");
        var oldCode = service.verify(signed(legacy, leaf)).verificationCode();
        assertThatThrownBy(() -> service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(
                pacs.clientId(), oldCode, PC_VERIFIER, PC_NONCE), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId(), null,
                PC_NONCE, PC_CHALLENGE, "S256"), "127.0.0.1");
        var code = service.verify(signed(challenge, leaf)).verificationCode();
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(
                pacs.clientId(), code, PC_VERIFIER, PC_NONCE), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
    }

    @Test
    void pcEventsRequireTheirOwnLiveRedeemedProofAndPreserveDashboardCounts() {
        var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId(), null,
                PC_NONCE, PC_CHALLENGE, "S256"), "127.0.0.1");
        var code = service.verify(signed(challenge, pki.leaf(TestPki.rsa(2048)))).verificationCode();
        var info = new PacsCertificateProofService.ClientInfo("reader-sp10", "42", "SP10-PC", "device-sp10");
        var receipt = service.redeemPc(new PacsCertificateProofService.PcRedeemRequest(pacs.clientId(), code,
                PC_VERIFIER, PC_NONCE, info), PacsRequestContext.EMPTY).receipt();
        var event = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "LOGIN_SUCCEEDED", info, null);
        var request = new PacsCertificateProofService.PcClientEventRequest(pacs.clientId(), receipt.verificationId(), PC_VERIFIER, event);
        assertThat(service.clientEventPc(request, PacsRequestContext.EMPTY).duplicate()).isFalse();
        assertThat(service.clientEventPc(request, PacsRequestContext.EMPTY).duplicate()).isTrue();
        assertThatThrownBy(() -> service.clientEventPc(new PacsCertificateProofService.PcClientEventRequest(
                pacs.clientId(), receipt.verificationId(), "x".repeat(43), event), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.clientEventPc(new PacsCertificateProofService.PcClientEventRequest(
                "other-client", receipt.verificationId(), PC_VERIFIER, event), PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        for (int i = 0; i < 2; i++) {
            var approval = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "APPROVAL_REQUESTED", info, "a".repeat(64));
            service.clientEventPc(new PacsCertificateProofService.PcClientEventRequest(pacs.clientId(), receipt.verificationId(),
                    PC_VERIFIER, approval), PacsRequestContext.EMPTY);
        }
        var page = new PacsProofActivityController(audit, IntegrationClients.of(pacs))
                .list(new RequestActor("hospital-a", "admin", ActorRole.ADMIN), "", 0, 50).getBody();
        assertThat(page.totals().loginSucceeded()).isEqualTo(1);
        assertThat(page.totals().approvalRequests()).isEqualTo(2);
        assertThat(page.totals().approvalDocuments()).isEqualTo(1);
        assertThat(page.certificates()).singleElement().satisfies(cert -> {
            assertThat(cert.accounts()).containsExactly("reader-sp10");
            assertThat(cert.computers()).containsExactly("SP10-PC");
        });
        assertThat(page.toString()).doesNotContain(PC_VERIFIER, code);
        assertThat(request.toString()).doesNotContain(PC_VERIFIER);
        clock.advance(Duration.ofHours(2));
        assertThatThrownBy(() -> service.clientEventPc(request, PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
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
    void pcProofBindingSurvivesPostgresRestart() throws Exception {
        var db = PostgresTestDatabase.create("pc_proof_binding");
        java.util.UUID proofId;
        String legacyCode;
        try (var store = db.store()) {
            var durableAudit = new AuditService(store, clock);
            var doctors = new DoctorService(durableAudit, new DevSimulatedCredentialProvider(clock),
                    new TestGoogleTokens(clock).verifier(), clock, DoctorPolicy.defaults(), false, true, true);
            var durable = new PacsCertificateProofService(IntegrationClients.of(pacs),
                    new CertificateValidator(trust, clock), doctors, durableAudit, clock, "test");
            var challenge = durable.start(new PacsCertificateProofService.StartRequest(pacs.clientId(), null,
                    PC_NONCE, PC_CHALLENGE, "S256"), "127.0.0.1");
            var code = durable.verify(signed(challenge, pki.leaf(TestPki.rsa(2048))));
            proofId = durable.redeemPc(new PacsCertificateProofService.PcRedeemRequest(pacs.clientId(),
                    code.verificationCode(), PC_VERIFIER, PC_NONCE), PacsRequestContext.EMPTY).receipt().verificationId();
            durable.activateAccount(new PacsCertificateProofService.AccountActivateRequest(pacs.clientId(),
                    proofId, PC_VERIFIER, ACCOUNT_KEY, BOOT, owner), PacsRequestContext.EMPTY);
            var legacy = durable.start(new PacsCertificateProofService.StartRequest(pacs.clientId()), "127.0.0.1");
            try (var connection = db.adminConnect(); var sql = connection.prepareStatement(
                    "UPDATE certi_rt.pacs_proof_challenges SET payload = payload - 'pcNonce' - 'codeChallenge' WHERE id = ?")) {
                sql.setObject(1, legacy.challengeId());
                assertThat(sql.executeUpdate()).isEqualTo(1);
            }
            legacyCode = durable.verify(signed(legacy, pki.leaf(TestPki.rsa(2048)))).verificationCode();
            try (var connection = db.adminConnect(); var sql = connection.createStatement()) {
                assertThat(sql.executeUpdate("UPDATE certi_rt.pacs_certificate_proofs SET payload = payload - 'pcNonce' - 'codeChallenge' - 'lease'"
                        + " WHERE payload->>'codeChallenge' IS NULL")).isEqualTo(1);
            }
        }
        try (var store = db.store()) {
            var durableAudit = new AuditService(store, clock);
            var doctors = new DoctorService(durableAudit, new DevSimulatedCredentialProvider(clock),
                    new TestGoogleTokens(clock).verifier(), clock, DoctorPolicy.defaults(), false, true, true);
            var reopened = new PacsCertificateProofService(IntegrationClients.of(pacs),
                    new CertificateValidator(trust, clock), doctors, durableAudit, clock, "test");
            var status = new PacsCertificateProofService.PcStatusRequest(pacs.clientId(), proofId, PC_VERIFIER);
            assertThat(reopened.statusPc(status, PacsRequestContext.EMPTY).verified()).isTrue();
            assertThat(reopened.accountStatus(new PacsCertificateProofService.AccountStatusRequest(pacs.clientId(), ACCOUNT_KEY, other),
                    PacsRequestContext.EMPTY).active()).isTrue();
            assertThat(reopened.redeem(pacs, new PacsCertificateProofService.RedeemRequest(legacyCode)).verified()).isTrue();
            clock.advance(Duration.ofHours(2));
            assertThatThrownBy(() -> reopened.statusPc(status, PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        }
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
        assertThat(audit.list("hospital-a")).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("PACS_CERTIFICATE_PROOF_DENIED");
            assertThat(event.details()).containsEntry("code", "CERTIFICATE_SIGNATURE_INVALID");
        });
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

    @Test
    void durableActivitySurvivesRestartExpiryAndProofPruningWithoutSecrets() throws Exception {
        var db = PostgresTestDatabase.create("proof_activity");
        String fingerprint;
        String codeValue;
        java.util.UUID durableProof;
        var durableEvent = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "LOGIN_SUCCEEDED",
                new PacsCertificateProofService.ClientInfo("durable-reader", "42", "DURABLE-PC", "device-durable"), null);
        var context = new PacsRequestContext("127.0.0.8", "203.0.113.8, 198.51.100.2", "PACS-test/3.40.6", "https://vision-certi.web.app");
        try (var store = db.store()) {
            audit = new AuditService(store, clock);
            DoctorService doctors = new DoctorService(audit, new DevSimulatedCredentialProvider(clock),
                    new TestGoogleTokens(clock).verifier(), clock, DoctorPolicy.defaults(), false, true, true);
            service = new PacsCertificateProofService(IntegrationClients.of(pacs), new CertificateValidator(trust, clock),
                    doctors, audit, clock, "test");
            var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId()), context);
            var leaf = pki.leaf(TestPki.rsa(2048));
            fingerprint = leaf.fingerprint();
            var code = service.verify(signed(challenge, leaf), context);
            codeValue = code.verificationCode();
            var receipt = service.redeem(pacs, new PacsCertificateProofService.RedeemRequest(codeValue), context);
            durableProof = receipt.verificationId();
            service.clientEvent(pacs, durableProof, durableEvent, context);
            service.status(pacs, receipt.verificationId(), context);
            assertThatThrownBy(() -> service.redeem(pacs, new PacsCertificateProofService.RedeemRequest(codeValue), context))
                    .isInstanceOf(ApiException.class);
            clock.advance(Duration.ofHours(3));
            assertThatThrownBy(() -> service.status(pacs, receipt.verificationId(), context)).isInstanceOf(ApiException.class);
            // Another proof prunes the old operational row, but never its audit evidence.
            trust.crls.clear();
            trust.crls.addAll(pki.freshCrls());
            var next = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId()), context);
            service.verify(signed(next, leaf), context);
            assertThat(audit.list(pacs.clientId())).isEmpty();
        }
        try (var reopened = db.store()) {
            var readAudit = new AuditService(reopened, clock);
            DoctorService reopenedDoctors = new DoctorService(readAudit, new DevSimulatedCredentialProvider(clock),
                    new TestGoogleTokens(clock).verifier(), clock, DoctorPolicy.defaults(), false, true, true);
            var reopenedService = new PacsCertificateProofService(IntegrationClients.of(pacs), new CertificateValidator(trust, clock), reopenedDoctors, readAudit, clock, "test");
            assertThat(reopenedService.clientEvent(pacs, durableProof, durableEvent, context).duplicate()).isTrue();
            var delayed = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "APPROVAL_REQUESTED", durableEvent.clientInfo(), "d".repeat(64));
            assertThat(reopenedService.clientEvent(pacs, durableProof, delayed, context).recorded()).isTrue();
            var controller = new PacsProofActivityController(readAudit, IntegrationClients.of(pacs));
            var admin = new RequestActor("hospital-a", "admin", ActorRole.ADMIN);
            var page = controller.list(admin, "", 0, 50).getBody();
            assertThat(page.totals().requested()).isEqualTo(2);
            assertThat(page.totals().verified()).isEqualTo(2);
            assertThat(page.totals().redeemed()).isEqualTo(1);
            assertThat(page.totals().checked()).isEqualTo(1);
            assertThat(page.totals().loginSucceeded()).isEqualTo(1);
            assertThat(page.totals().approvalDocuments()).isEqualTo(1);
            assertThat(page.totals().denied()).isEqualTo(2);
            assertThat(page.certificates()).singleElement().satisfies(cert -> {
                assertThat(cert.fingerprintSha256()).isEqualTo(fingerprint);
                assertThat(cert.subjectDn()).isNotBlank();
                assertThat(cert.issuerDn()).isNotBlank();
                assertThat(cert.notAfter()).isNotBlank();
            });
            assertThat(page.events()).allSatisfy(event -> assertThat(event.details()).containsEntry("peerIp", "127.0.0.8"));
            assertThat(page.toString()).doesNotContain(codeValue, "certificateDer", "privateKey", "password");
            assertThat(controller.list(admin, "203.0.113.8", 1, 2).getBody().events()).hasSize(2);
            assertThat(controller.list(new RequestActor("other", "admin", ActorRole.ADMIN), "", 0, 50).getBody().total()).isZero();
            assertThatThrownBy(() -> controller.list(new RequestActor("hospital-a", "doctor", ActorRole.DOCTOR), "", 0, 50))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void legacyChainIsVisibleOnlyToItsSingleTenantAndIsNeverRewritten() {
        audit.record(pacs.clientId(), "certificate", "PACS_CERTIFICATE_PROOF_VERIFIED", "CERTIFICATE", "old-proof",
                AuditService.SUCCESS, java.util.Map.of("fingerprintSha256", "a".repeat(64)));
        var original = audit.list(pacs.clientId());
        var admin = new RequestActor("hospital-a", "admin", ActorRole.ADMIN);
        var controller = new PacsProofActivityController(audit, IntegrationClients.of(pacs));
        assertThat(controller.list(admin, "", 0, 50).getBody().events()).singleElement()
                .satisfies(event -> assertThat(event.legacy()).isTrue());
        assertThat(controller.list(new RequestActor("other", "admin", ActorRole.ADMIN), "", 0, 50).getBody().total()).isZero();
        var multi = new IntegrationClient(pacs.clientId(), new byte[32], "test", Set.of("hospital-a", "other"));
        assertThat(new PacsProofActivityController(audit, IntegrationClients.of(multi)).list(admin, "", 0, 50).getBody().total()).isZero();
        assertThat(audit.list(pacs.clientId())).isEqualTo(original);
    }

    @Test
    void clientLoginAndApprovalEventsAreScopedDeduplicatedAndCountReportsSeparately() {
        var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId()), "127.0.0.1");
        var leaf = pki.leaf(TestPki.rsa(2048));
        var code = service.verify(signed(challenge, leaf));
        var info = new PacsCertificateProofService.ClientInfo("doctor-login", "42", "READING-PC", "device-123");
        var receipt = service.redeem(pacs, new PacsCertificateProofService.RedeemRequest(code.verificationCode(), info));
        var attempt = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "LOGIN_ATTEMPTED", info, null);
        assertThat(service.clientEvent(pacs, receipt.verificationId(), attempt, PacsRequestContext.EMPTY).duplicate()).isFalse();
        assertThat(service.clientEvent(pacs, receipt.verificationId(), attempt, PacsRequestContext.EMPTY).duplicate()).isTrue();
        var success = new PacsCertificateProofService.ClientEventRequest(java.util.UUID.randomUUID(), "LOGIN_SUCCEEDED", info, null);
        service.clientEvent(pacs, receipt.verificationId(), success, PacsRequestContext.EMPTY);
        for (int i = 0; i < 3; i++) {
            service.clientEvent(pacs, receipt.verificationId(), new PacsCertificateProofService.ClientEventRequest(
                    java.util.UUID.randomUUID(), "APPROVAL_REQUESTED", info, (i == 2 ? "b" : "a").repeat(64)), PacsRequestContext.EMPTY);
        }
        var changed = new PacsCertificateProofService.ClientEventRequest(attempt.eventId(), "LOGIN_SUCCEEDED", info, null);
        assertThatThrownBy(() -> service.clientEvent(pacs, receipt.verificationId(), changed, PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        var otherClient = new IntegrationClient("other", new byte[32], "test", Set.of("other"));
        assertThatThrownBy(() -> service.clientEvent(otherClient, receipt.verificationId(), success, PacsRequestContext.EMPTY)).isInstanceOf(ApiException.class);
        var page = new PacsProofActivityController(audit, IntegrationClients.of(pacs))
                .list(new RequestActor("hospital-a", "admin", ActorRole.ADMIN), "", 0, 50).getBody();
        assertThat(page.totals().loginAttempted()).isEqualTo(1);
        assertThat(page.totals().loginSucceeded()).isEqualTo(1);
        assertThat(page.totals().approvalRequests()).isEqualTo(3);
        assertThat(page.totals().approvalDocuments()).isEqualTo(2);
        assertThat(page.certificates()).singleElement().satisfies(cert -> {
            assertThat(cert.attempted()).isEqualTo(1);
            assertThat(cert.accounts()).containsExactly("doctor-login");
            assertThat(cert.computers()).containsExactly("READING-PC");
            assertThat(cert.loginSucceeded()).isEqualTo(1);
        });
        assertThat(page.toString()).doesNotContain(code.verificationCode(), "password", "privateKey");
    }

    @Test
    void rejectedSignatureIsCountedAgainstTheSubmittedCertificate() {
        var challenge = service.start(new PacsCertificateProofService.StartRequest(pacs.clientId()), "127.0.0.1");
        var leaf = pki.leaf(TestPki.rsa(2048));
        var signed = signed(challenge, leaf);
        var invalid = new LoginRequest(signed.challengeId(), signed.fingerprintSha256(), signed.certificate(), signed.chain(), "AA==", signed.signatureAlgorithm());
        assertThatThrownBy(() -> service.verify(invalid)).isInstanceOf(ApiException.class);
        var page = new PacsProofActivityController(audit, IntegrationClients.of(pacs))
                .list(new RequestActor("hospital-a", "admin", ActorRole.ADMIN), "", 0, 50).getBody();
        assertThat(page.certificates()).singleElement().satisfies(cert -> {
            assertThat(cert.attempted()).isEqualTo(1); assertThat(cert.verified()).isZero();
        });
    }
}
