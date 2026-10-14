package com.inviz.visioncerti.doctor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorDtos.ChallengeResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ChangeDoctorStatus;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregisterDoctor;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregistrationResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.SessionResponse;
import com.inviz.visioncerti.doctor.credential.CredentialException;
import com.inviz.visioncerti.doctor.credential.DevSimulatedCredentialProvider;
import com.inviz.visioncerti.doctor.credential.DoctorCredential;
import com.inviz.visioncerti.doctor.credential.DoctorCredentialProvider;
import com.inviz.visioncerti.doctor.credential.NotConfiguredCredentialProvider;
import com.inviz.visioncerti.doctor.identity.TestGoogleTokens;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.RegisteredDoctorSigner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Physician state machine, invitation/challenge/session rules, roles and tenant scope with a controllable clock. */
class DoctorServiceTest {

    private static final String TENANT_A = "tenant-a";
    private static final RequestActor ADMIN_A = new RequestActor(TENANT_A, "admin-1", ActorRole.ADMIN);
    private static final RequestActor ADMIN_B = new RequestActor("tenant-b", "admin-2", ActorRole.ADMIN);
    private static final RequestActor DOCTOR_ROLE = new RequestActor(TENANT_A, "doctor-demo", ActorRole.DOCTOR);
    private static final ConsentRequest CONSENT = new ConsentRequest("2026-10-13-draft", true, true, true);

    private MutableClock clock;
    private AuditService audit;
    private TestGoogleTokens google;
    private DevSimulatedCredentialProvider simulated;
    private DoctorService service;
    private final AtomicInteger licenses = new AtomicInteger(1000);

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
        audit = new AuditService(clock);
        google = new TestGoogleTokens(clock);
        simulated = new DevSimulatedCredentialProvider(clock);
        service = newService(simulated, true);
    }

    /** These tests exercise the Google path under the dev-only GOOGLE_PERMITTED_DEV policy (certificateRequired=false). */
    private DoctorService newService(DoctorCredentialProvider provider, boolean devIdentity) {
        return new DoctorService(audit, provider, google.verifier(), clock, DoctorPolicy.defaults(), devIdentity, false);
    }

    private PreregistrationResponse preregister(DoctorService s, String email) {
        return s.preregister(ADMIN_A, new PreregisterDoctor("김의사", Integer.toString(licenses.incrementAndGet()),
                "서울영상센터", email, "evidence://id/1", "evidence://license/1"));
    }

    private SessionResponse verifyGoogle(DoctorService s, String invitationCode, String subject, String email) {
        ChallengeResponse c = s.createActivationChallenge(invitationCode, "10.0.0.1");
        return s.completeGoogle(c.challengeId(), google.token(subject, email, c.nonce()));
    }

    private DoctorPrincipal principal(DoctorService s, SessionResponse session) {
        return s.authenticate("Bearer " + session.activationToken());
    }

    private DoctorPrincipal activeDoctor(DoctorService s, String email, String subject) {
        PreregistrationResponse pre = preregister(s, email);
        DoctorPrincipal p = principal(s, verifyGoogle(s, pre.invitationCode(), subject, email));
        s.consent(p, CONSENT);
        s.activate(p);
        return p;
    }

    private static String code(Throwable t) {
        return ((ApiException) t).code();
    }

    private static HttpStatus status(Throwable t) {
        return ((ApiException) t).status();
    }

    @Test
    void fullLifecycleCreatesKeyOnlyAfterConsent() {
        PreregistrationResponse pre = preregister(service, "Dr.Kim@Example.com");
        DoctorResponse d = pre.doctor();
        assertThat(d.status()).isEqualTo(DoctorStatus.PENDING);
        assertThat(d.certificate()).isNull();
        assertThat(d.googleEmail()).isEqualTo("dr.kim@example.com");
        assertThat(pre.invitationCode()).matches("([A-HJ-NP-Z2-9]{5}-){4}[A-HJ-NP-Z2-9]{5}");
        assertThat(pre.toString()).doesNotContain(pre.invitationCode());
        assertThat(pre.invitationExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(72)));

        SessionResponse session = verifyGoogle(service, pre.invitationCode().toLowerCase(), "google-sub-1",
                "dr.kim@example.com");
        assertThat(session.identityMode()).isEqualTo("GOOGLE");
        assertThat(session.doctor().status()).isEqualTo(DoctorStatus.IDENTITY_VERIFIED);
        assertThat(session.doctor().googleAccountLinked()).isTrue();
        assertThat(session.doctor().certificate()).isNull();
        assertThat(session.activationToken()).matches("[A-Za-z0-9_-]{43}");

        DoctorPrincipal p = principal(service, session);
        assertThat(service.consent(p, CONSENT).status()).isEqualTo(DoctorStatus.CONSENTED);
        assertThat(service.isActiveSigner(TENANT_A, d.id().toString())).isFalse();

        DoctorResponse active = service.activate(p);
        assertThat(active.status()).isEqualTo(DoctorStatus.ACTIVE);
        assertThat(active.certificate().kind()).isEqualTo("SIMULATED_NOT_X509");
        assertThat(active.certificate().certificatePem()).isNull();
        assertThat(active.certificate().status()).isEqualTo("VALID");
        assertThat(service.isActiveSigner(TENANT_A, d.id().toString())).isTrue();
        assertThat(service.isActiveSigner("tenant-b", d.id().toString())).isFalse();
        // Idempotent.
        assertThat(service.activate(p).certificate().serialNumber()).isEqualTo(active.certificate().serialNumber());

        List<DoctorEvidence> evidence = service.evidence(ADMIN_A, d.id());
        assertThat(evidence).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("AdminVerification", "LoginEvidence", "ConsentEvidence");

        // Audit never carries the invitation code, nonce, session token or email.
        for (AuditEvent event : audit.list(TENANT_A)) {
            assertThat(event.details().toString()).doesNotContain(pre.invitationCode())
                    .doesNotContain(session.activationToken()).doesNotContain("example.com");
        }
    }

    @Test
    void invitationIsSingleUseAndBadCodesAreRateLimitedPerSource() {
        PreregistrationResponse pre = preregister(service, "a@example.com");
        verifyGoogle(service, pre.invitationCode(), "sub-a", "a@example.com");
        assertThatThrownBy(() -> service.createActivationChallenge(pre.invitationCode(), "10.0.0.1"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));

        for (int i = 0; i < 9; i++) {
            assertThatThrownBy(() -> service.createActivationChallenge("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", "10.9.9.9"))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
        }
        assertThatThrownBy(() -> service.createActivationChallenge("short", "10.9.9.9"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
        assertThatThrownBy(() -> service.createActivationChallenge("AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", "10.9.9.9"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void invitationExpiresAndReissueVoidsOldCode() {
        PreregistrationResponse pre = preregister(service, "b@example.com");
        clock.advance(Duration.ofHours(72));
        assertThatThrownBy(() -> service.createActivationChallenge(pre.invitationCode(), "s"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
        assertThat(service.get(ADMIN_A, pre.doctor().id()).invitationState()).isEqualTo("EXPIRED");

        PreregistrationResponse again = service.reissueInvitation(ADMIN_A, pre.doctor().id());
        assertThat(again.invitationCode()).isNotEqualTo(pre.invitationCode());
        assertThatThrownBy(() -> service.createActivationChallenge(pre.invitationCode(), "s"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
        assertThat(verifyGoogle(service, again.invitationCode(), "sub-b", "b@example.com").doctor().status())
                .isEqualTo(DoctorStatus.IDENTITY_VERIFIED);
        assertThatThrownBy(() -> service.reissueInvitation(ADMIN_A, pre.doctor().id()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_DOCTOR_STATE"));
    }

    @Test
    void challengeIsSingleUseExpiresAndNonceIsBound() {
        PreregistrationResponse pre = preregister(service, "c@example.com");
        ChallengeResponse c = service.createActivationChallenge(pre.invitationCode(), "s");
        ChallengeResponse other = service.createActivationChallenge(pre.invitationCode(), "s");
        // A token minted for one challenge's nonce is rejected on another challenge.
        assertThatThrownBy(() -> service.completeGoogle(other.challengeId(),
                google.token("sub-c", "c@example.com", c.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("ID_TOKEN_INVALID"));
        assertThatThrownBy(() -> service.completeGoogle(other.challengeId(),
                google.token("sub-c", "c@example.com", other.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_ALREADY_USED"));

        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.completeGoogle(c.challengeId(), google.token("sub-c", "c@example.com",
                c.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_EXPIRED"));
        assertThatThrownBy(() -> service.completeGoogle(UUID.randomUUID().toString(), "x"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHALLENGE_NOT_FOUND"));
    }

    @Test
    void wrongAccountAttemptsLockTheInvitation() {
        PreregistrationResponse pre = preregister(service, "d@example.com");
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> verifyGoogle(service, pre.invitationCode(), "sub-x", "intruder@example.com"))
                    .satisfies(t -> {
                        assertThat(code(t)).isEqualTo("GOOGLE_EMAIL_MISMATCH");
                        assertThat(status(t)).isEqualTo(HttpStatus.FORBIDDEN);
                    });
        }
        assertThatThrownBy(() -> verifyGoogle(service, pre.invitationCode(), "sub-x", "intruder@example.com"))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("INVITATION_LOCKED");
                    assertThat(status(t)).isEqualTo(HttpStatus.LOCKED);
                });
        assertThatThrownBy(() -> verifyGoogle(service, pre.invitationCode(), "sub-d", "d@example.com"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVITATION_INVALID"));
        assertThat(service.get(ADMIN_A, pre.doctor().id()).invitationState()).isEqualTo("LOCKED");
        assertThat(service.get(ADMIN_A, pre.doctor().id()).status()).isEqualTo(DoctorStatus.PENDING);
    }

    @Test
    void googleAccountCanBindToOnlyOneDoctor() {
        activeDoctor(service, "e1@example.com", "shared-sub");
        PreregistrationResponse second = preregister(service, "e2@example.com");
        assertThatThrownBy(() -> verifyGoogle(service, second.invitationCode(), "shared-sub", "e2@example.com"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("GOOGLE_ACCOUNT_ALREADY_BOUND"));
        assertThatThrownBy(() -> preregister(service, "E1@example.com"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("GOOGLE_EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void onlyAdminsOfTheSameTenantManageDoctors() {
        PreregistrationResponse pre = preregister(service, "f@example.com");
        UUID id = pre.doctor().id();
        assertThatThrownBy(() -> service.preregister(DOCTOR_ROLE, new PreregisterDoctor("이름", "1", "센터명",
                "x@example.com", "r", "r")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("ADMIN_ROLE_REQUIRED"));
        assertThatThrownBy(() -> service.list(DOCTOR_ROLE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("ADMIN_ROLE_REQUIRED"));
        assertThatThrownBy(() -> service.get(ADMIN_B, id))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_FOUND"));
        assertThatThrownBy(() -> service.changeStatus(ADMIN_B, id, new ChangeDoctorStatus("SUSPENDED", "사유입니다")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_FOUND"));
        assertThatThrownBy(() -> service.reissueInvitation(ADMIN_B, id))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_FOUND"));
        assertThat(service.list(ADMIN_B)).isEmpty();
        assertThat(service.list(ADMIN_A)).extracting(DoctorResponse::id).contains(id);
        assertThatThrownBy(() -> service.preregister(ADMIN_A, new PreregisterDoctor("이름", "12ab", "센터명",
                "y@example.com", "r", "r")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void transitionsMustFollowTheStateMachine() {
        PreregistrationResponse pre = preregister(service, "g@example.com");
        DoctorPrincipal p = principal(service, verifyGoogle(service, pre.invitationCode(), "sub-g", "g@example.com"));
        assertThatThrownBy(() -> service.activate(p))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_DOCTOR_STATE"));
        assertThatThrownBy(() -> service.consent(p, new ConsentRequest("2026-10-13-draft", true, false, true)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CONSENT_REQUIRED"));
        assertThatThrownBy(() -> service.consent(p, new ConsentRequest("old", true, true, true)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CONSENT_VERSION_MISMATCH"));
        service.consent(p, CONSENT);
        assertThatThrownBy(() -> service.consent(p, CONSENT))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_DOCTOR_STATE"));
    }

    @Test
    void activationFailsClosedAndRetrySucceeds() {
        DoctorService unconfigured = newService(new NotConfiguredCredentialProvider("NOT_CONFIGURED", "not set"), false);
        PreregistrationResponse pre = preregister(unconfigured, "h@example.com");
        DoctorPrincipal p = principal(unconfigured, verifyGoogle(unconfigured, pre.invitationCode(), "sub-h",
                "h@example.com"));
        unconfigured.consent(p, CONSENT);
        assertThatThrownBy(() -> unconfigured.activate(p)).satisfies(t -> {
            assertThat(code(t)).isEqualTo("SIGNING_BACKEND_NOT_CONFIGURED");
            assertThat(status(t)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
        assertThat(unconfigured.me(p).doctor().status()).isEqualTo(DoctorStatus.CONSENTED);
        assertThat(unconfigured.me(p).certificate()).isNull();

        AtomicInteger calls = new AtomicInteger();
        DoctorCredentialProvider flaky = new DevSimulatedCredentialProvider(clock) {
            @Override
            public synchronized DoctorCredential provision(String doctorId) {
                if (calls.incrementAndGet() == 1) {
                    throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "connection refused");
                }
                return super.provision(doctorId);
            }
        };
        DoctorService flakyService = newService(flaky, false);
        PreregistrationResponse pre2 = preregister(flakyService, "i@example.com");
        DoctorPrincipal p2 = principal(flakyService, verifyGoogle(flakyService, pre2.invitationCode(), "sub-i",
                "i@example.com"));
        flakyService.consent(p2, CONSENT);
        assertThatThrownBy(() -> flakyService.activate(p2)).satisfies(t -> {
            assertThat(code(t)).isEqualTo("SIGNING_BACKEND_UNAVAILABLE");
            assertThat(((ApiException) t).retryable()).isTrue();
        });
        assertThat(flakyService.me(p2).doctor().status()).isEqualTo(DoctorStatus.CONSENTED);
        assertThat(flakyService.activate(p2).status()).isEqualTo(DoctorStatus.ACTIVE);
    }

    @Test
    void suspensionAndRevocationTakeEffectImmediately() {
        PreregistrationResponse pre = preregister(service, "j@example.com");
        SessionResponse session = verifyGoogle(service, pre.invitationCode(), "sub-j", "j@example.com");
        DoctorPrincipal p = principal(service, session);
        service.consent(p, CONSENT);
        service.activate(p);
        RegisteredDoctorSigner signer = service.signerFor(p);
        assertThat(signer.assertCanSign().keyName()).isEqualTo("dev-sim-" + p.doctorId());

        service.changeStatus(ADMIN_A, p.doctorId(), new ChangeDoctorStatus("SUSPENDED", "면허 확인 필요"));
        // The existing session is dropped: the next request is refused.
        assertThatThrownBy(() -> service.authenticate("Bearer " + session.activationToken()))
                .satisfies(t -> assertThat(status(t)).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN));
        assertThatThrownBy(signer::assertCanSign)
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_ACTIVE"));
        assertThat(service.isActiveSigner(TENANT_A, p.doctorId().toString())).isFalse();
        // Login is refused while suspended.
        ChallengeResponse login = service.createLoginChallenge("s");
        assertThatThrownBy(() -> service.completeGoogle(login.challengeId(),
                google.token("sub-j", "j@example.com", login.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_NOT_ALLOWED"));

        DoctorResponse revoked = service.changeStatus(ADMIN_A, p.doctorId(),
                new ChangeDoctorStatus("REVOKED", "면허 취소"));
        assertThat(revoked.certificate().status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.changeStatus(ADMIN_A, p.doctorId(),
                new ChangeDoctorStatus("SUSPENDED", "다시")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("INVALID_DOCTOR_STATE"));
        assertThatThrownBy(() -> service.changeStatus(ADMIN_A, p.doctorId(), new ChangeDoctorStatus("ACTIVE", "x")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void sessionsAreCheckedEveryTimeAndExpire() {
        PreregistrationResponse pre = preregister(service, "k@example.com");
        SessionResponse session = verifyGoogle(service, pre.invitationCode(), "sub-k", "k@example.com");
        assertThat(service.authenticate("Bearer " + session.activationToken()).tenantId()).isEqualTo(TENANT_A);
        assertThatThrownBy(() -> service.authenticate(session.activationToken()))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_SESSION_REQUIRED"));
        assertThatThrownBy(() -> service.authenticate("Bearer " + "A".repeat(43)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCTOR_SESSION_REQUIRED"));
        clock.advance(Duration.ofHours(1));
        assertThatThrownBy(() -> service.authenticate("Bearer " + session.activationToken()))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void returningDoctorLogsInWithBoundGoogleSubjectOnly() {
        activeDoctor(service, "l@example.com", "sub-l");
        ChallengeResponse login = service.createLoginChallenge("s");
        assertThat(login.purpose()).isEqualTo("LOGIN");
        SessionResponse session = service.completeGoogle(login.challengeId(),
                google.token("sub-l", "changed-email@example.com", login.nonce()));
        assertThat(session.doctor().status()).isEqualTo(DoctorStatus.ACTIVE);

        ChallengeResponse stranger = service.createLoginChallenge("s");
        assertThatThrownBy(() -> service.completeGoogle(stranger.challengeId(),
                google.token("sub-unknown", "l@example.com", stranger.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("GOOGLE_ACCOUNT_NOT_LINKED"));
    }

    @Test
    void devIdentityIsOptInAndNeverSatisfiesGoogle() {
        DoctorService noDev = newService(simulated, false);
        PreregistrationResponse pre0 = preregister(noDev, "m0@example.com");
        ChallengeResponse c0 = noDev.createActivationChallenge(pre0.invitationCode(), "s");
        assertThatThrownBy(() -> noDev.completeDevIdentity(c0.challengeId(), "m0@example.com", "sub"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DEV_IDENTITY_DISABLED"));

        PreregistrationResponse pre = preregister(service, "m@example.com");
        ChallengeResponse c = service.createActivationChallenge(pre.invitationCode(), "s");
        SessionResponse session = service.completeDevIdentity(c.challengeId(), "m@example.com", "mock-sub");
        assertThat(session.identityMode()).isEqualTo("MOCK_IDENTITY");
        assertThat(session.doctor().googleAccountLinked()).isFalse();

        // A Google token with the same subject is a different binding (different issuer).
        ChallengeResponse login = service.createLoginChallenge("s");
        assertThatThrownBy(() -> service.completeGoogle(login.challengeId(),
                google.token("mock-sub", "m@example.com", login.nonce())))
                .satisfies(t -> assertThat(code(t)).isEqualTo("GOOGLE_ACCOUNT_NOT_LINKED"));
    }

    @Test
    void googleUnavailableWithoutClientId() {
        DoctorService noGoogle = new DoctorService(audit, simulated,
                new com.inviz.visioncerti.doctor.identity.GoogleIdTokenVerifier(null, google.jwks(), clock), clock,
                DoctorPolicy.defaults(), false, false);
        assertThat(noGoogle.config().googleConfigured()).isFalse();
        assertThat(noGoogle.config().clinicalUseAllowed()).isFalse();
        assertThatThrownBy(() -> noGoogle.completeGoogle(UUID.randomUUID().toString(), "x"))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("GOOGLE_NOT_CONFIGURED");
                    assertThat(status(t)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    @Test
    void certificateRequiredPolicyRefusesGoogleOnlyActivation() {
        DoctorService strict = new DoctorService(audit, simulated, google.verifier(), clock, DoctorPolicy.defaults(),
                false, true);
        assertThat(strict.config().activationPolicy()).isEqualTo("CERTIFICATE_REQUIRED");
        PreregistrationResponse pre = preregister(strict, "strict@example.com");
        DoctorPrincipal p = principal(strict, verifyGoogle(strict, pre.invitationCode(), "sub-strict",
                "strict@example.com"));
        strict.consent(p, CONSENT);
        assertThatThrownBy(() -> strict.activate(p)).satisfies(t -> {
            assertThat(code(t)).isEqualTo("CERTIFICATE_ENROLLMENT_REQUIRED");
            assertThat(status(t)).isEqualTo(HttpStatus.CONFLICT);
        });
        assertThat(strict.me(p).doctor().status()).isEqualTo(DoctorStatus.CONSENTED);
        assertThat(strict.me(p).certificate()).isNull();
    }

    @Test
    void googlePermittedPolicyIsDevOnly() {
        assertThat(DoctorService.certificateRequired(null, false)).isTrue();
        assertThat(DoctorService.certificateRequired("CERTIFICATE_REQUIRED", false)).isTrue();
        assertThat(DoctorService.certificateRequired("GOOGLE_PERMITTED_DEV", true)).isFalse();
        assertThatThrownBy(() -> DoctorService.certificateRequired("GOOGLE_PERMITTED_DEV", false))
                .hasMessageContaining("FAIL_CLOSED");
        assertThatThrownBy(() -> DoctorService.certificateRequired("ANYTHING", true))
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    void expiredCertificateBlocksSigning() {
        DoctorPrincipal p = activeDoctor(service, "n@example.com", "sub-n");
        RegisteredDoctorSigner signer = service.signerFor(p);
        clock.advance(Duration.ofDays(366));
        assertThatThrownBy(signer::assertCanSign)
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERTIFICATE_NOT_VALID"));
    }
}
