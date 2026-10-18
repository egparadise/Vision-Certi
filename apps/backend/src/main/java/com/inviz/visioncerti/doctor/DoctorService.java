package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.admin.SystemSettings;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.Doctor.InvitationState;
import com.inviz.visioncerti.doctor.DoctorDtos.CertificateResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ChallengeResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ChangeDoctorStatus;
import com.inviz.visioncerti.doctor.DoctorDtos.ConfigResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.MeResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregisterDoctor;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregistrationResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.SessionResponse;
import com.inviz.visioncerti.doctor.credential.CredentialException;
import com.inviz.visioncerti.doctor.credential.DoctorCredential;
import com.inviz.visioncerti.doctor.credential.DoctorCredentialProvider;
import com.inviz.visioncerti.doctor.identity.GoogleIdTokenVerifier;
import com.inviz.visioncerti.doctor.identity.VerifiedIdentity;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.RegisteredDoctorSigner;
import com.inviz.visioncerti.signing.RegisteredSigners;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import com.inviz.visioncerti.store.Tx;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Physician lifecycle: admin preregistration with a one-time invitation, identity verification (Google ID token
 * or dev MOCK_IDENTITY) bound to a single-use nonce challenge, the physician's own consent, activation that
 * provisions a key + certificate, bearer sessions, and admin suspension/revocation.
 *
 * <p>All state is durable ({@link Store}): doctors, challenges, sessions (token SHA-256 only), invitation hashes,
 * per-source rate events and evidence are rows shared by every instance and kept across restarts. Each operation is
 * one storage transaction with row locks; network calls (JWKS, OpenBao) run between transactions, never inside one.
 * Activation is guarded by a persisted lease so a retry after a crash reuses the same OpenBao key name.
 */
@Service
public class DoctorService implements RegisteredSigners {

    public static final String CONSENT_VERSION = "2026-10-13-draft";

    private static final String INVITATION_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern INVITATION = Pattern.compile("[A-HJ-NP-Z2-9]{25}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern LICENSE = Pattern.compile("[0-9]{1,20}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    /** An activation attempt older than this (crash, restart) no longer blocks a retry. */
    static final Duration ACTIVATION_LEASE = Duration.ofMinutes(2);
    private static final Duration PRUNE_INTERVAL = Duration.ofSeconds(30);

    enum Purpose { ACTIVATION, LOGIN }

    /**
     * Single-use identity challenge. {@code used} marks a consumed challenge, kept until its original expiry for a
     * precise replay error; an unused one is kept one more TTL so late attempts get CHALLENGE_EXPIRED.
     */
    record Challenge(UUID id, Purpose purpose, String nonce, UUID doctorId, String invitationHash, Instant expiresAt,
            boolean used, Instant retainUntil) {
    }

    /** Bearer session; only the SHA-256 of the token is stored. The id is the session id. */
    record Session(UUID sessionId, String tokenHash, UUID doctorId, String tenantId, String identityMode,
            Instant expiresAt, String certificateFingerprint, Instant authenticatedAt) {
    }

    /** One counted event (failure or challenge) for a hashed source key within the rate window. */
    record RateEvent(UUID id, String bucket, Instant at, Instant expiresAt) {
    }

    /** Evidence row: exactly one of the three record kinds is set. */
    record StoredEvidence(UUID id, UUID doctorId, String tenantId, DoctorEvidence.AdminVerification admin,
            DoctorEvidence.LoginEvidence login, DoctorEvidence.ConsentEvidence consent) {

        static StoredEvidence of(DoctorEvidence e) {
            return new StoredEvidence(evidenceId(e), e.doctorId(), e.tenantId(),
                    e instanceof DoctorEvidence.AdminVerification a ? a : null,
                    e instanceof DoctorEvidence.LoginEvidence l ? l : null,
                    e instanceof DoctorEvidence.ConsentEvidence c ? c : null);
        }

        DoctorEvidence value() {
            return admin != null ? admin : login != null ? login : consent;
        }

        private static UUID evidenceId(DoctorEvidence e) {
            return switch (e) {
                case DoctorEvidence.AdminVerification a -> a.id();
                case DoctorEvidence.LoginEvidence l -> l.id();
                case DoctorEvidence.ConsentEvidence c -> c.id();
            };
        }
    }

    static final Kind<Doctor> DOCTORS = Kind.of("doctors", Doctor.class)
            .id(d -> d.id).tenant(d -> d.tenantId)
            .unique("license_key", d -> d.status == DoctorStatus.REVOKED ? null
                            : Hashing.sha256Hex(d.tenantId + "\u0000" + d.licenseNumber),
                    () -> ApiException.conflict("DOCTOR_LICENSE_ALREADY_REGISTERED", "이 기관에 이미 등록된 면허번호입니다."))
            .unique("email_key", d -> d.status == DoctorStatus.REVOKED || d.googleEmail == null ? null : Hashing.sha256Hex(d.googleEmail),
                    () -> ApiException.conflict("GOOGLE_EMAIL_ALREADY_REGISTERED", "이미 다른 의사에게 등록된 Google 이메일입니다."))
            .unique("invitation_hash", d -> d.invitationState == InvitationState.ISSUED ? d.invitationHash : null, null)
            .unique("binding_key", DoctorService::bindingColumn,
                    () -> ApiException.conflict("GOOGLE_ACCOUNT_ALREADY_BOUND", "이 계정은 이미 다른 의사 계정에 연결되어 있습니다."))
            .unique("pacs_account_key", d -> d.pacsAccountKey,
                    () -> ApiException.conflict("PACS_ACCOUNT_ALREADY_BOUND", "이미 연결된 PACS 계정입니다."))
            .build();

    static final Kind<Challenge> CHALLENGES = Kind.of("doctor_challenges", Challenge.class)
            .id(Challenge::id).expiresAt(Challenge::retainUntil)
            .column("doctor_id", c -> c.doctorId() == null ? null : c.doctorId().toString())
            .build();

    static final Kind<Session> SESSIONS = Kind.of("doctor_sessions", Session.class)
            .id(Session::sessionId).tenant(Session::tenantId).expiresAt(Session::expiresAt)
            .unique("token_hash", Session::tokenHash, null)
            .column("doctor_id", s -> s.doctorId().toString())
            .build();

    static final Kind<RateEvent> RATE_EVENTS = Kind.of("rate_events", RateEvent.class)
            .id(RateEvent::id).expiresAt(RateEvent::expiresAt)
            .column("bucket", RateEvent::bucket)
            .build();

    static final Kind<StoredEvidence> EVIDENCE = Kind.of("doctor_evidence", StoredEvidence.class)
            .id(StoredEvidence::id).tenant(StoredEvidence::tenantId)
            .column("doctor_id", e -> e.doctorId().toString())
            .immutable()
            .build();

    /** A live certificate-login session re-validated for an external integration (no token, no secret). */
    public record IntegrationIdentity(UUID doctorId, String tenantId, UUID sessionId, String identityMode,
            UUID certificateEnrollmentId, String certificateFingerprint, Instant authenticatedAt,
            Instant sessionExpiresAt) {
    }

    private final AuditService audit;
    private final Store store;
    private final DoctorCredentialProvider credentials;
    private final GoogleIdTokenVerifier google;
    private final Clock clock;
    private final DoctorPolicy basePolicy;
    private final boolean devIdentityEnabled;
    private final boolean adminInvitationsEnabled;
    private final SecureRandom random = new SecureRandom();
    private volatile Instant lastPrune = Instant.EPOCH;

    /** True under the default CERTIFICATE_REQUIRED policy: activation and signing need an ACTIVE certificate enrollment. */
    private final boolean certificateRequired;
    private volatile CertificateGate certificateGate;

    public static final String POLICY_CERTIFICATE_REQUIRED = "CERTIFICATE_REQUIRED";
    public static final String POLICY_GOOGLE_PERMITTED_DEV = "GOOGLE_PERMITTED_DEV";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    /** Identity proven by a certificate (or the dev MOCK_CERTIFICATE shortcut). */
    public record CertificateIdentity(UUID doctorId, String invitationHash, String identityMode, String issuer,
            String subject, String purpose, UUID challengeId) {
    }

    /** Invitation resolved for a certificate enrollment challenge. */
    public record CertificateInvitation(UUID doctorId, String tenantId, String invitationHash) {
    }

    /** Minimal doctor facts for the certificate service. */
    public record DoctorRef(UUID id, String tenantId, DoctorStatus status, String expectedCertificateFingerprint) {
    }

    @Autowired
    public DoctorService(AuditService audit, DoctorCredentialProvider credentials, GoogleIdTokenVerifier google,
            Clock clock, DoctorPolicy policy, Environment environment,
            @Value("${visioncerti.doctor.dev-identity-enabled:false}") boolean devIdentityFlag,
            @Value("${visioncerti.doctor.activation-policy:CERTIFICATE_REQUIRED}") String activationPolicy,
            @Value("${visioncerti.doctor.admin-invitations-enabled:false}") boolean adminInvitationsEnabled) {
        this(audit, credentials, google, clock, policy,
                devIdentityFlag && environment.acceptsProfiles(Profiles.of("dev")),
                certificateRequired(activationPolicy, environment.acceptsProfiles(Profiles.of("dev"))),
                adminInvitationsEnabled);
    }

    public DoctorService(AuditService audit, DoctorCredentialProvider credentials, GoogleIdTokenVerifier google,
            Clock clock, DoctorPolicy policy, boolean devIdentityEnabled, boolean certificateRequired) {
        this(audit, credentials, google, clock, policy, devIdentityEnabled, certificateRequired, false);
    }

    public DoctorService(AuditService audit, DoctorCredentialProvider credentials, GoogleIdTokenVerifier google,
            Clock clock, DoctorPolicy policy, boolean devIdentityEnabled, boolean certificateRequired,
            boolean adminInvitationsEnabled) {
        this.audit = audit;
        this.store = audit.store();
        this.credentials = credentials;
        this.google = google;
        this.clock = clock;
        this.basePolicy = policy;
        this.devIdentityEnabled = devIdentityEnabled;
        this.certificateRequired = certificateRequired;
        this.adminInvitationsEnabled = adminInvitationsEnabled;
    }

    private volatile SystemSettings settings;

    /** Administrator overrides ({@link SystemSettings}); absent in unit tests that construct the service directly. */
    @Autowired(required = false)
    public void useSettings(SystemSettings settings) {
        this.settings = settings;
    }

    /** The deployment policy with administrator overrides applied, read fresh for each decision. */
    private DoctorPolicy policy() {
        SystemSettings s = settings;
        return s == null ? basePolicy : s.doctorPolicy(basePolicy);
    }

    static boolean certificateRequired(String activationPolicy, boolean devProfile) {
        String value = activationPolicy == null ? "" : activationPolicy.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty() || value.equals(POLICY_CERTIFICATE_REQUIRED)) {
            return true;
        }
        if (value.equals(POLICY_GOOGLE_PERMITTED_DEV)) {
            if (!devProfile) {
                throw new IllegalStateException("FAIL_CLOSED: GOOGLE_PERMITTED_DEV is only allowed in the dev profile");
            }
            return false;
        }
        throw new IllegalStateException("FAIL_CLOSED: unknown VISIONCERTI_DOCTOR_ACTIVATION_POLICY");
    }

    /** Called once by the certificate enrollment service; without a gate, certificate-required activation fails. */
    public void attachCertificateGate(CertificateGate gate) {
        this.certificateGate = gate;
    }

    public boolean certificateRequired() {
        return certificateRequired;
    }

    public String activationPolicy() {
        return certificateRequired ? POLICY_CERTIFICATE_REQUIRED : POLICY_GOOGLE_PERMITTED_DEV;
    }

    public ConfigResponse config() {
        return new ConfigResponse(google.clientId(), google.configured(), devIdentityEnabled, credentials.backend(),
                credentials.available(), false, activationPolicy(), adminInvitationsEnabled);
    }

    public void requireAdminInvitationsEnabled() {
        if (!adminInvitationsEnabled) {
            throw ApiException.forbidden("ADMIN_INVITATIONS_DISABLED",
                    "관리자 사전등록 및 초대 코드 등록은 현재 중지되어 있습니다. PACS에서 본인 인증서 등록을 진행해 주세요.");
        }
    }

    public static boolean isCertificateMode(String identityMode) {
        return "PC_CERTIFICATE".equals(identityMode) || "MOBILE_CERTIFICATE".equals(identityMode)
                || "MOCK_CERTIFICATE".equals(identityMode);
    }

    private boolean hasActiveEnrollment(UUID doctorId) {
        CertificateGate gate = certificateGate;
        return gate != null && gate.hasActiveEnrollment(doctorId);
    }

    public boolean devIdentityEnabled() {
        return devIdentityEnabled;
    }

    /** Google / dev identity binding (issuer|subject) of a non-revoked doctor; certificate identities have none. */
    private static String bindingColumn(Doctor d) {
        if (d.status == DoctorStatus.REVOKED || d.identityIssuer == null || isCertificateMode(d.identityMode)) {
            return null;
        }
        return Hashing.sha256Hex(d.bindingKey());
    }

    // ------------------------------------------------------------------ admin

    public PreregistrationResponse preregister(RequestActor admin, PreregisterDoctor body) {
        requireAdmin(admin);
        requireAdminInvitationsEnabled();
        if (body == null) {
            throw invalid("요청 본문이 필요합니다.");
        }
        String name = text(body.name(), 2, 50, "이름은 2자 이상 50자 이하여야 합니다.");
        String license = body.licenseNumber() == null ? null : body.licenseNumber().strip();
        if (license == null || !LICENSE.matcher(license).matches()) {
            throw invalid("면허번호는 숫자 1~20자리여야 합니다.");
        }
        String center = text(body.centerName(), 2, 100, "소속 센터명은 2자 이상 100자 이하여야 합니다.");
        String email = body.googleEmail() == null ? null : body.googleEmail().strip().toLowerCase(Locale.ROOT);
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw invalid("Google 이메일 형식이 올바르지 않습니다.");
        }
        String identityRef = text(body.identityEvidenceRef(), 1, 200, "신원 확인 증빙 참조는 1자 이상 200자 이하여야 합니다.");
        String licenseRef = text(body.licenseEvidenceRef(), 1, 200, "면허 확인 증빙 참조는 1자 이상 200자 이하여야 합니다.");
        String expectedFingerprint = null;
        if (body.expectedCertificateFingerprint() != null && !body.expectedCertificateFingerprint().isBlank()) {
            String fp = body.expectedCertificateFingerprint().replace(":", "").strip().toLowerCase(Locale.ROOT);
            if (!FINGERPRINT.matcher(fp).matches()) {
                throw invalid("인증서 지문은 SHA-256 16진수 64자리여야 합니다.");
            }
            expectedFingerprint = fp;
        }
        String expected = expectedFingerprint;
        return store.tx(tx -> {
            tx.lock("doctor-license|" + admin.tenantId() + "|" + license);
            tx.lock("doctor-email|" + email);
            if (tx.findOne(DOCTORS, "license_key", Hashing.sha256Hex(admin.tenantId() + "\u0000" + license))
                    .isPresent()) {
                throw ApiException.conflict("DOCTOR_LICENSE_ALREADY_REGISTERED", "이 기관에 이미 등록된 면허번호입니다.");
            }
            if (tx.findOne(DOCTORS, "email_key", Hashing.sha256Hex(email)).isPresent()) {
                throw ApiException.conflict("GOOGLE_EMAIL_ALREADY_REGISTERED", "이미 다른 의사에게 등록된 Google 이메일입니다.");
            }
            if (tx.countAll(DOCTORS) >= policy().maxDoctors()) {
                throw capacity();
            }
            Instant now = clock.instant();
            Doctor doctor = new Doctor(UUID.randomUUID(), admin.tenantId(), name, license, center, email, identityRef,
                    licenseRef, now, admin.actorId());
            doctor.expectedCertificateFingerprint = expected;
            String code = issueInvitation(doctor, now);
            tx.insert(DOCTORS, doctor);
            addEvidence(tx, new DoctorEvidence.AdminVerification(UUID.randomUUID(), doctor.id, doctor.tenantId,
                    admin.actorId(), identityRef, licenseRef, now));
            audit.record(admin.tenantId(), admin.actorId(), "DOCTOR_PREREGISTERED", "DOCTOR", doctor.id.toString(),
                    AuditService.SUCCESS, Map.of("identityEvidenceRef", identityRef, "licenseEvidenceRef", licenseRef,
                            "invitationExpiresAt", doctor.invitationExpiresAt.toString()));
            return new PreregistrationResponse(toResponse(doctor), code, doctor.invitationExpiresAt);
        });
    }

    public PreregistrationResponse reissueInvitation(RequestActor admin, UUID id) {
        requireAdmin(admin);
        requireAdminInvitationsEnabled();
        return store.tx(tx -> {
            Doctor doctor = lockInTenant(tx, admin.tenantId(), id);
            boolean reEnrollment = !doctor.status.blocked() && doctor.status != DoctorStatus.PENDING
                    && certificateGate != null && !hasActiveEnrollment(doctor.id);
            if (doctor.status != DoctorStatus.PENDING && !reEnrollment) {
                throw ApiException.conflict("INVALID_DOCTOR_STATE",
                        "초대 코드는 PENDING 상태이거나 활성 인증서 등록이 없는 의사에게만 재발급할 수 있습니다.");
            }
            Instant now = clock.instant();
            deleteOpenChallenges(tx, doctor.id);
            String code = issueInvitation(doctor, now);
            tx.update(DOCTORS, doctor);
            audit.record(admin.tenantId(), admin.actorId(), "DOCTOR_INVITATION_REISSUED", "DOCTOR",
                    doctor.id.toString(), AuditService.SUCCESS,
                    Map.of("invitationExpiresAt", doctor.invitationExpiresAt.toString()));
            return new PreregistrationResponse(toResponse(doctor), code, doctor.invitationExpiresAt);
        });
    }

    public List<DoctorResponse> list(RequestActor admin) {
        requireAdmin(admin);
        return store.tx(tx -> tx.find(DOCTORS, Kind.TENANT, admin.tenantId()).stream()
                .sorted(Comparator.comparing((Doctor d) -> d.createdAt).reversed())
                .map(this::toResponse)
                .toList());
    }

    public DoctorResponse get(RequestActor admin, UUID id) {
        requireAdmin(admin);
        return store.tx(tx -> toResponse(findInTenant(tx, admin.tenantId(), id)));
    }

    public List<DoctorEvidence> evidence(RequestActor admin, UUID id) {
        requireAdmin(admin);
        return store.tx(tx -> {
            Doctor doctor = findInTenant(tx, admin.tenantId(), id);
            return tx.find(EVIDENCE, "doctor_id", doctor.id.toString()).stream().map(StoredEvidence::value).toList();
        });
    }

    public DoctorResponse changeStatus(RequestActor admin, UUID id, ChangeDoctorStatus body) {
        requireAdmin(admin);
        DoctorCredential[] toRevoke = new DoctorCredential[1];
        DoctorResponse response = store.tx(tx -> {
            toRevoke[0] = null;
            Doctor doctor = lockInTenant(tx, admin.tenantId(), id);
            DoctorStatus target;
            if (body != null && "SUSPENDED".equals(body.status())) {
                target = DoctorStatus.SUSPENDED;
            } else if (body != null && "REVOKED".equals(body.status())) {
                target = DoctorStatus.REVOKED;
            } else {
                throw invalid("status는 SUSPENDED 또는 REVOKED여야 합니다.");
            }
            String reason = text(body.reason(), 2, 200, "사유는 2자 이상 200자 이하여야 합니다.");
            if (doctor.status == DoctorStatus.REVOKED || doctor.status == target) {
                throw ApiException.conflict("INVALID_DOCTOR_STATE", "현재 상태에서는 변경할 수 없습니다.");
            }
            Instant now = clock.instant();
            DoctorStatus previous = doctor.status;
            doctor.status = target;
            doctor.statusReason = reason;
            doctor.statusChangedAt = now;
            tx.deleteWhere(SESSIONS, "doctor_id", doctor.id.toString());
            deleteOpenChallenges(tx, doctor.id);
            if (doctor.invitationState == InvitationState.ISSUED) {
                doctor.invitationState = InvitationState.VOID;
            }
            if (target == DoctorStatus.REVOKED && doctor.credential != null && !doctor.credentialRevoked) {
                doctor.credentialRevoked = true;
                toRevoke[0] = doctor.credential;
            }
            tx.update(DOCTORS, doctor);
            audit.record(admin.tenantId(), admin.actorId(), "DOCTOR_" + target.name(), "DOCTOR", doctor.id.toString(),
                    AuditService.SUCCESS, Map.of("previousStatus", previous.name(), "reason", reason));
            return toResponse(doctor);
        });
        if (toRevoke[0] != null) {
            DoctorCredential credential = toRevoke[0];
            String outcome;
            try {
                credentials.revoke(credential);
                outcome = AuditService.SUCCESS;
            } catch (RuntimeException e) {
                outcome = "FAILED";
            }
            audit.record(admin.tenantId(), admin.actorId(), "DOCTOR_CERTIFICATE_REVOKE_REQUESTED", "DOCTOR",
                    id.toString(), outcome, Map.of("certificateId", credential.serialNumber(),
                            "backend", credential.backend()));
        }
        return response;
    }

    // ------------------------------------------------------------------ challenges and identity

    public ChallengeResponse createActivationChallenge(String invitationCode, String source) {
        requireAdminInvitationsEnabled();
        return store.tx(tx -> {
            Instant now = clock.instant();
            prune(tx, now);
            String sourceKey = sourceKey(source);
            if (rateCount(tx, "failure", sourceKey, now) >= policy().sourceFailureHourlyLimit()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "INVITATION_ATTEMPTS_EXCEEDED",
                        "초대 코드 입력 실패가 너무 많습니다. 잠시 후 다시 시도해 주세요.", true);
            }
            requireChallengeQuota(tx, sourceKey, now);
            Doctor doctor = doctorByInvitation(tx, invitationCode);
            boolean usable = doctor != null && invitationUsable(tx, doctor, now)
                    && doctor.invitationChallenges < policy().maxChallengesPerInvitation();
            if (!usable) {
                rateEvent(tx, "failure", sourceKey, now);
                throw ApiException.unprocessable("INVITATION_INVALID",
                        "초대 코드가 올바르지 않거나 만료되었거나 이미 사용되었습니다. 관리자에게 문의해 주세요.");
            }
            doctor.invitationChallenges++;
            tx.update(DOCTORS, doctor);
            return newChallenge(tx, Purpose.ACTIVATION, doctor.id, doctor.invitationHash, sourceKey, now);
        });
    }

    public ChallengeResponse createLoginChallenge(String source) {
        return store.tx(tx -> {
            Instant now = clock.instant();
            prune(tx, now);
            String sourceKey = sourceKey(source);
            requireChallengeQuota(tx, sourceKey, now);
            return newChallenge(tx, Purpose.LOGIN, null, null, sourceKey, now);
        });
    }

    public SessionResponse completeGoogle(String challengeId, String idToken) {
        if (!google.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_NOT_CONFIGURED",
                    "Google 로그인이 설정되지 않았습니다 (VISIONCERTI_GOOGLE_CLIENT_ID).");
        }
        Challenge challenge = claimChallenge(challengeId);
        VerifiedIdentity identity;
        try {
            identity = google.verify(idToken, challenge.nonce());
        } catch (GoogleIdTokenVerifier.IdTokenException e) {
            ApiException locked = store.tx(tx -> recordIdentityFailure(tx, challenge, "ID_TOKEN_" + e.reason()));
            if (locked != null) {
                throw locked;
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ID_TOKEN_INVALID",
                    "Google 인증 토큰을 검증하지 못했습니다. 처음부터 다시 시도해 주세요.");
        }
        return finishIdentity(challenge, identity);
    }

    public SessionResponse completeDevIdentity(String challengeId, String email, String subject) {
        if (!devIdentityEnabled) {
            throw ApiException.notFound("DEV_IDENTITY_DISABLED", "개발용 모의 신원 확인이 비활성화되어 있습니다.");
        }
        String mail = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        if (mail == null || mail.length() > 254 || !EMAIL.matcher(mail).matches()) {
            throw invalid("이메일 형식이 올바르지 않습니다.");
        }
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw invalid("subject는 1자 이상 255자 이하여야 합니다.");
        }
        Challenge challenge = claimChallenge(challengeId);
        return finishIdentity(challenge,
                new VerifiedIdentity(VerifiedIdentity.MOCK_IDENTITY, VerifiedIdentity.MOCK_ISSUER, subject, mail));
    }

    /** Single use: any identity attempt with a valid challenge consumes it (durably, before verification). */
    private Challenge claimChallenge(String challengeId) {
        return store.tx(tx -> {
            Instant now = clock.instant();
            prune(tx, now);
            UUID id = parseUuid(challengeId);
            Challenge challenge = id == null ? null : tx.lockGet(CHALLENGES, id).orElse(null);
            if (challenge != null && challenge.used()) {
                if (now.isBefore(challenge.expiresAt())) {
                    throw ApiException.conflict("CHALLENGE_ALREADY_USED",
                            "이미 사용된 인증 요청입니다. 처음부터 다시 시도해 주세요.");
                }
                challenge = null;
            }
            if (challenge == null || !now.isBefore(challenge.retainUntil())) {
                throw ApiException.notFound("CHALLENGE_NOT_FOUND", "인증 요청을 찾을 수 없습니다. 처음부터 다시 시도해 주세요.");
            }
            if (!now.isBefore(challenge.expiresAt())) {
                tx.delete(CHALLENGES, id);
                throw new ApiException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "인증 요청이 만료되었습니다. 처음부터 다시 시도해 주세요.");
            }
            Challenge used = new Challenge(challenge.id(), challenge.purpose(), challenge.nonce(), challenge.doctorId(),
                    challenge.invitationHash(), challenge.expiresAt(), true, challenge.expiresAt());
            tx.update(CHALLENGES, used);
            if (used.purpose() == Purpose.ACTIVATION) {
                requireAdminInvitationsEnabled();
                Doctor doctor = tx.get(DOCTORS, used.doctorId()).orElse(null);
                if (doctor != null && doctor.invitationState == InvitationState.LOCKED) {
                    throw invitationLocked();
                }
            }
            return used;
        });
    }

    private SessionResponse finishIdentity(Challenge challenge, VerifiedIdentity identity) {
        return store.tx(tx -> {
            Instant now = clock.instant();
            Doctor doctor;
            String bindingKey = Hashing.sha256Hex(identity.bindingKey());
            if (challenge.purpose() == Purpose.ACTIVATION) {
                requireAdminInvitationsEnabled();
                doctor = tx.lockGet(DOCTORS, challenge.doctorId()).orElse(null);
                if (doctor == null || doctor.invitationState == InvitationState.LOCKED) {
                    throw doctor == null ? invitationInvalid() : invitationLocked();
                }
                if (!challenge.invitationHash().equals(doctor.invitationHash) || !invitationUsable(tx, doctor, now)) {
                    throw invitationInvalid();
                }
                if (!identity.email().equals(doctor.googleEmail)) {
                    ApiException locked = recordIdentityFailure(tx, challenge, "EMAIL_MISMATCH");
                    throw locked != null ? locked : new ApiException(HttpStatus.FORBIDDEN, "GOOGLE_EMAIL_MISMATCH",
                            "초대받은 Google 계정과 다른 계정입니다. 등록된 이메일의 계정으로 로그인해 주세요.");
                }
                tx.lock("doctor-binding|" + bindingKey);
                Doctor bound = tx.findOne(DOCTORS, "binding_key", bindingKey).orElse(null);
                if (bound != null && !bound.id.equals(doctor.id)) {
                    throw ApiException.conflict("GOOGLE_ACCOUNT_ALREADY_BOUND", "이 계정은 이미 다른 의사 계정에 연결되어 있습니다.");
                }
                doctor.identityMode = identity.mode();
                doctor.identityIssuer = identity.issuer();
                doctor.identitySubject = identity.subject();
                doctor.identityVerifiedAt = now;
                doctor.status = DoctorStatus.IDENTITY_VERIFIED;
                doctor.invitationState = InvitationState.USED;
                deleteOpenChallenges(tx, doctor.id);
                tx.update(DOCTORS, doctor);
                audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_IDENTITY_VERIFIED", "DOCTOR",
                        doctor.id.toString(), AuditService.SUCCESS,
                        Map.of("identityMode", identity.mode(), "issuer", identity.issuer()));
            } else {
                doctor = tx.findOne(DOCTORS, "binding_key", bindingKey).orElse(null);
                if (doctor == null) {
                    throw ApiException.forbidden("GOOGLE_ACCOUNT_NOT_LINKED",
                            "이 계정에 연결된 의사 계정이 없습니다. 초대 코드로 먼저 활성화해 주세요.");
                }
                if (doctor.status.blocked()) {
                    audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_LOGIN", "DOCTOR", doctor.id.toString(),
                            AuditService.DENIED, Map.of("status", doctor.status.name()));
                    throw notAllowed();
                }
                audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_LOGIN", "DOCTOR", doctor.id.toString(),
                        AuditService.SUCCESS, Map.of("identityMode", identity.mode()));
            }
            addEvidence(tx, new DoctorEvidence.LoginEvidence(UUID.randomUUID(), doctor.id, doctor.tenantId,
                    identity.mode(), identity.issuer(), identity.subject(), challenge.purpose().name(), challenge.id(),
                    now));
            return newSession(tx, doctor, identity.mode(), null, now);
        });
    }

    /** Counts a failed identity attempt against the invitation; returns the lock error once the limit is hit. */
    private ApiException recordIdentityFailure(Tx tx, Challenge challenge, String reason) {
        if (challenge.purpose() != Purpose.ACTIVATION) {
            return null;
        }
        Doctor doctor = tx.lockGet(DOCTORS, challenge.doctorId()).orElse(null);
        if (doctor == null) {
            return null;
        }
        return countInvitationFailure(tx, doctor, "DOCTOR_IDENTITY_REJECTED", reason);
    }

    private ApiException countInvitationFailure(Tx tx, Doctor doctor, String action, String reason) {
        doctor.invitationFailures++;
        audit.record(doctor.tenantId, "anonymous", action, "DOCTOR", doctor.id.toString(),
                AuditService.DENIED, Map.of("reason", reason, "failures", Integer.toString(doctor.invitationFailures)));
        ApiException result = null;
        if (doctor.invitationFailures >= policy().maxIdentityFailuresPerInvitation()
                && doctor.invitationState == InvitationState.ISSUED) {
            doctor.invitationState = InvitationState.LOCKED;
            result = invitationLocked();
        }
        tx.update(DOCTORS, doctor);
        return result;
    }

    // ------------------------------------------------------------------ sessions, consent, activation

    public DoctorPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw sessionRequired();
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (!TOKEN.matcher(token).matches()) {
            throw sessionRequired();
        }
        String key = Hashing.sha256Hex(token);
        return store.tx(tx -> {
            Instant now = clock.instant();
            Session session = tx.findOne(SESSIONS, "token_hash", key).orElse(null);
            if (session == null) {
                throw sessionRequired();
            }
            if (!now.isBefore(session.expiresAt())) {
                tx.delete(SESSIONS, session.sessionId());
                throw sessionRequired();
            }
            Doctor doctor = tx.get(DOCTORS, session.doctorId()).orElse(null);
            if (doctor == null || !doctor.tenantId.equals(session.tenantId())) {
                tx.delete(SESSIONS, session.sessionId());
                throw sessionRequired();
            }
            if (doctor.status.blocked()) {
                tx.deleteWhere(SESSIONS, "doctor_id", doctor.id.toString());
                throw notAllowed();
            }
            return new DoctorPrincipal(doctor.id, doctor.tenantId, session.identityMode(), session.expiresAt(),
                    session.sessionId(), session.certificateFingerprint(), session.authenticatedAt());
        });
    }

    public DoctorResponse consent(DoctorPrincipal principal, ConsentRequest body) {
        return store.tx(tx -> {
            Doctor doctor = load(tx, principal);
            if (doctor.status != DoctorStatus.IDENTITY_VERIFIED) {
                throw ApiException.conflict("INVALID_DOCTOR_STATE", "신원 확인 직후(IDENTITY_VERIFIED)에만 동의할 수 있습니다.");
            }
            validateConsent(body);
            return markConsented(tx, doctor, principal);
        });
    }

    private static void validateConsent(ConsentRequest body) {
        if (body == null || !Boolean.TRUE.equals(body.authentication()) || !Boolean.TRUE.equals(body.signature())
                || !Boolean.TRUE.equals(body.privacy())) {
            throw ApiException.unprocessable("CONSENT_REQUIRED", "필수 동의(인증, 서명, 개인정보 처리)에 모두 동의해야 합니다.");
        }
        if (!CONSENT_VERSION.equals(body.version())) {
            throw ApiException.unprocessable("CONSENT_VERSION_MISMATCH", "동의서 버전이 최신이 아닙니다. 새로고침 후 다시 동의해 주세요.");
        }
    }

    private DoctorResponse markConsented(Tx tx, Doctor doctor, DoctorPrincipal principal) {
        Instant now = clock.instant();
        doctor.status = DoctorStatus.CONSENTED;
        doctor.consentedAt = now;
        doctor.consentVersion = CONSENT_VERSION;
        tx.update(DOCTORS, doctor);
        addEvidence(tx, new DoctorEvidence.ConsentEvidence(UUID.randomUUID(), doctor.id, doctor.tenantId,
                CONSENT_VERSION, principal.identityMode(), now));
        audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_CONSENTED", "DOCTOR", doctor.id.toString(),
                AuditService.SUCCESS, Map.of("version", CONSENT_VERSION));
        return toResponse(doctor);
    }

    /**
     * Provisions the physician's key and certificate. A persisted lease marks the attempt; the OpenBao call runs
     * outside any transaction. A retry after a crash or restart (lease expired) provisions again under the same
     * deterministic key name, so the existing Transit key is reused rather than a new identity being created.
     */
    public DoctorResponse activate(DoctorPrincipal principal) {
        DoctorResponse done = store.tx(tx -> {
            Doctor doctor = load(tx, principal);
            if (doctor.status == DoctorStatus.ACTIVE) {
                return toResponse(doctor);
            }
            if (doctor.status != DoctorStatus.CONSENTED) {
                throw ApiException.conflict("INVALID_DOCTOR_STATE", "본인 동의(CONSENTED) 후에만 활성화할 수 있습니다.");
            }
            Instant now = clock.instant();
            if (doctor.activationInProgress && doctor.activationStartedAt != null
                    && now.isBefore(doctor.activationStartedAt.plus(ACTIVATION_LEASE))) {
                throw ApiException.conflict("ACTIVATION_IN_PROGRESS", "활성화가 이미 진행 중입니다.");
            }
            if (certificateRequired
                    && (!isCertificateMode(principal.identityMode()) || !hasActiveEnrollment(doctor.id))) {
                audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_ACTIVATION_FAILED", "DOCTOR",
                        doctor.id.toString(), AuditService.DENIED, Map.of("reason", "CERTIFICATE_ENROLLMENT_REQUIRED"));
                throw ApiException.conflict("CERTIFICATE_ENROLLMENT_REQUIRED",
                        "인증서 등록(관리자 확인 및 본인 동의 완료)과 인증서 로그인 세션이 있어야 활성화할 수 있습니다.");
            }
            if (!credentials.available()) {
                audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_ACTIVATION_FAILED", "DOCTOR",
                        doctor.id.toString(), AuditService.DENIED, Map.of("reason", "NOT_CONFIGURED"));
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIGNING_BACKEND_NOT_CONFIGURED",
                        "서명 키 백엔드가 설정되지 않아 활성화할 수 없습니다: " + credentials.unavailableReason());
            }
            doctor.activationInProgress = true;
            doctor.activationStartedAt = now;
            tx.update(DOCTORS, doctor);
            return null;
        });
        if (done != null) {
            return done;
        }
        UUID doctorId = principal.doctorId();
        DoctorCredential credential;
        try {
            credential = credentials.provision(doctorId.toString());
        } catch (RuntimeException e) {
            store.run(tx -> {
                Doctor doctor = tx.lockGet(DOCTORS, doctorId).orElseThrow();
                doctor.activationInProgress = false;
                doctor.activationStartedAt = null;
                tx.update(DOCTORS, doctor);
                String kind = e instanceof CredentialException ce ? ce.kind().name() : "UNEXPECTED";
                audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_ACTIVATION_FAILED", "DOCTOR",
                        doctor.id.toString(), AuditService.DENIED, Map.of("reason", kind));
            });
            throw activationError(e);
        }
        return store.tx(tx -> {
            Doctor doctor = tx.lockGet(DOCTORS, doctorId).orElseThrow(DoctorService::sessionRequired);
            doctor.activationInProgress = false;
            doctor.activationStartedAt = null;
            if (doctor.status != DoctorStatus.CONSENTED) {
                tx.update(DOCTORS, doctor);
                throw notAllowed();
            }
            Instant now = clock.instant();
            doctor.credential = credential;
            doctor.credentialRevoked = false;
            doctor.status = DoctorStatus.ACTIVE;
            doctor.activatedAt = now;
            tx.update(DOCTORS, doctor);
            audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_ACTIVATED", "DOCTOR", doctor.id.toString(),
                    AuditService.SUCCESS, Map.of(
                            "backend", credential.backend(),
                            "certificateId", credential.serialNumber(),
                            "certificateKind", credential.certificateKind(),
                            "keyName", credential.keyName(),
                            "keyVersion", Integer.toString(credential.keyVersion()),
                            "publicKeySha256", credential.publicKeySha256()));
            return toResponse(doctor);
        });
    }

    public MeResponse me(DoctorPrincipal principal) {
        return store.tx(tx -> {
            Doctor doctor = tx.get(DOCTORS, principal.doctorId()).orElse(null);
            requireUsable(doctor, principal);
            DoctorResponse response = toResponse(doctor);
            return new MeResponse(response, response.certificate(), principal.identityMode(),
                    principal.sessionExpiresAt());
        });
    }

    // ------------------------------------------------------------------ signing

    @Override
    public boolean isActiveSigner(String tenantId, String signerId) {
        UUID id = parseUuid(signerId);
        if (id == null) {
            return false;
        }
        return store.tx(tx -> {
            Doctor doctor = tx.get(DOCTORS, id).orElse(null);
            return doctor != null && doctor.tenantId.equals(tenantId) && doctor.status == DoctorStatus.ACTIVE
                    && doctor.credential != null && !doctor.credentialRevoked
                    && (!certificateRequired || hasActiveEnrollment(id));
        });
    }

    @Override
    public String signatureMode() {
        return credentials.signatureMode();
    }

    /** Signing capability bound to this authenticated physician; every check is repeated at use. */
    public RegisteredDoctorSigner signerFor(DoctorPrincipal principal) {
        return new RegisteredDoctorSigner() {
            @Override
            public String tenantId() {
                return principal.tenantId();
            }

            @Override
            public String doctorId() {
                return principal.doctorId().toString();
            }

            @Override
            public SignerSnapshot assertCanSign() {
                CertificateGate.EnrollmentBinding binding = null;
                if (certificateRequired) {
                    binding = requireGate().assertSigningAllowed(principal.doctorId());
                    requireSessionBoundTo(binding);
                }
                CertificateGate.EnrollmentBinding bound = binding;
                record Checked(DoctorCredential credential, SignerSnapshot snapshot) {
                }
                Checked checked = store.tx(tx -> {
                    Doctor doctor = tx.get(DOCTORS, principal.doctorId()).orElse(null);
                    if (doctor == null || !doctor.tenantId.equals(principal.tenantId())
                            || doctor.status != DoctorStatus.ACTIVE || doctor.credential == null) {
                        throw ApiException.forbidden("DOCTOR_NOT_ACTIVE", "활성(ACTIVE) 상태의 의사만 서명할 수 있습니다.");
                    }
                    DoctorCredential credential = doctor.credential;
                    if (doctor.credentialRevoked || !credential.withinValidity(clock.instant())) {
                        throw certificateNotValid();
                    }
                    requireLiveSession(tx);
                    return new Checked(credential, new SignerSnapshot(doctor.id.toString(), doctor.identityMode,
                            doctor.identityIssuer, doctor.identitySubject, credential.backend(),
                            credentials.signatureMode(), credentials.cryptographicallyAttributable(),
                            credentials.checksRevocationRemotely(), credential.serialNumber(),
                            credential.certificateKind(), credential.keyName(), credential.keyVersion(),
                            credential.publicKeySpki(),
                            bound == null ? null : bound.registrationId().toString(),
                            bound == null ? null : bound.fingerprintSha256()));
                });
                try {
                    credentials.assertNotRevoked(checked.credential());
                } catch (CredentialException e) {
                    if (e.kind() == CredentialException.Kind.CERTIFICATE_NOT_VALID) {
                        throw certificateNotValid();
                    }
                    throw signingUnavailable(e);
                }
                return checked.snapshot();
            }

            @Override
            public byte[] signDigest(SignerSnapshot snapshot, byte[] sha256Digest) {
                DoctorCredential credential = currentCredential(snapshot);
                try {
                    return credentials.signDigest(credential, sha256Digest);
                } catch (CredentialException e) {
                    throw signingUnavailable(e);
                }
            }

            @Override
            public void confirmStillAuthorized(SignerSnapshot snapshot) {
                if (certificateRequired) {
                    // The same enrollment that was validated before the remote operation must still be ACTIVE:
                    // a renewal, unregister or external revocation meanwhile discards the signature.
                    if (snapshot.certificateEnrollmentId() == null) {
                        throw enrollmentChanged();
                    }
                    CertificateGate.EnrollmentBinding binding = new CertificateGate.EnrollmentBinding(
                            UUID.fromString(snapshot.certificateEnrollmentId()),
                            snapshot.certificateEnrollmentFingerprint(), null);
                    requireGate().confirmBinding(principal.doctorId(), binding);
                    requireSessionBoundTo(binding);
                }
                currentCredential(snapshot);
            }

            private CertificateGate requireGate() {
                CertificateGate gate = certificateGate;
                if (gate == null) {
                    throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_REQUIRED", "활성 인증서 등록이 필요합니다.");
                }
                return gate;
            }

            /** A certificate-login session may only sign with the enrollment whose certificate it proved. */
            private void requireSessionBoundTo(CertificateGate.EnrollmentBinding binding) {
                if (isCertificateMode(principal.identityMode())
                        && !binding.fingerprintSha256().equals(principal.certificateFingerprint())) {
                    throw enrollmentChanged();
                }
            }

            /** Unregister/suspension deletes sessions; the stored session must still exist and be unexpired. */
            private void requireLiveSession(Tx tx) {
                Instant now = clock.instant();
                Session session = tx.get(SESSIONS, principal.sessionId()).orElse(null);
                if (session == null || !session.doctorId().equals(principal.doctorId())
                        || !now.isBefore(session.expiresAt())) {
                    throw sessionRequired();
                }
            }

            private DoctorCredential currentCredential(SignerSnapshot snapshot) {
                return store.tx(tx -> {
                    Doctor doctor = tx.get(DOCTORS, principal.doctorId()).orElse(null);
                    if (doctor == null || !doctor.tenantId.equals(principal.tenantId())
                            || doctor.status != DoctorStatus.ACTIVE || doctor.credential == null
                            || !doctor.credential.keyName().equals(snapshot.keyName())
                            || doctor.credential.keyVersion() != snapshot.keyVersion()
                            || !doctor.credential.serialNumber().equals(snapshot.certificateId())) {
                        throw ApiException.forbidden("DOCTOR_NOT_ACTIVE", "활성(ACTIVE) 상태의 의사만 서명할 수 있습니다.");
                    }
                    if (doctor.credentialRevoked || !doctor.credential.withinValidity(clock.instant())) {
                        throw certificateNotValid();
                    }
                    requireLiveSession(tx);
                    if (certificateRequired && !hasActiveEnrollment(doctor.id)) {
                        throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_REQUIRED", "활성 인증서 등록이 필요합니다.");
                    }
                    return doctor.credential;
                });
            }
        };
    }

    // ------------------------------------------------------------------ external integrations (PACS handoff)

    /**
     * Requires a certificate-login session that is at most {@code maxAge} old, an ACTIVE doctor with a signing
     * credential, and an ACTIVE enrollment that re-validates now (path + CRL) and is the certificate the session
     * proved. Used before issuing a PACS handoff code.
     */
    public IntegrationIdentity requireFreshCertificateSession(DoctorPrincipal principal, Duration maxAge) {
        if (!isCertificateMode(principal.identityMode()) || principal.certificateFingerprint() == null) {
            throw ApiException.forbidden("CERTIFICATE_SESSION_REQUIRED", "인증서 로그인 세션이 필요합니다.");
        }
        Instant now = clock.instant();
        if (principal.authenticatedAt() == null || principal.authenticatedAt().plus(maxAge).isBefore(now)) {
            throw ApiException.forbidden("CERTIFICATE_LOGIN_TOO_OLD", "인증서로 다시 로그인해 주세요.");
        }
        CertificateGate gate = certificateGate;
        if (gate == null) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_REQUIRED", "활성 인증서 등록이 필요합니다.");
        }
        CertificateGate.EnrollmentBinding binding = gate.assertSigningAllowed(principal.doctorId());
        if (!binding.fingerprintSha256().equals(principal.certificateFingerprint())) {
            throw enrollmentChanged();
        }
        return confirmIntegrationSession(principal.doctorId(), principal.sessionId(), binding.registrationId());
    }

    /**
     * Re-checks, without network I/O, that a certificate session is still live for an ACTIVE signer and that
     * {@code enrollmentId} is still the doctor's ACTIVE enrollment proved by that session.
     */
    public IntegrationIdentity confirmIntegrationSession(UUID doctorId, UUID sessionId, UUID enrollmentId) {
        CertificateGate gate = certificateGate;
        IntegrationIdentity identity = store.tx(tx -> {
            Instant now = clock.instant();
            Session session = tx.get(SESSIONS, sessionId).filter(s -> s.doctorId().equals(doctorId)).orElse(null);
            if (session == null || !now.isBefore(session.expiresAt())) {
                throw sessionRequired();
            }
            Doctor doctor = tx.get(DOCTORS, doctorId).orElse(null);
            if (doctor == null || !doctor.tenantId.equals(session.tenantId()) || doctor.status != DoctorStatus.ACTIVE
                    || doctor.credential == null || doctor.credentialRevoked) {
                throw ApiException.forbidden("DOCTOR_NOT_ACTIVE", "활성(ACTIVE) 상태의 의사가 아닙니다.");
            }
            return new IntegrationIdentity(doctorId, doctor.tenantId, sessionId, session.identityMode(),
                    enrollmentId, session.certificateFingerprint(), session.authenticatedAt(), session.expiresAt());
        });
        if (gate == null) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_REQUIRED", "활성 인증서 등록이 필요합니다.");
        }
        gate.confirmBinding(doctorId, new CertificateGate.EnrollmentBinding(enrollmentId,
                identity.certificateFingerprint(), null));
        return identity;
    }

    /** Extend a still-live certificate session by one hour after the PACS HMAC caller and enrollment are checked. */
    public IntegrationIdentity extendIntegrationSession(UUID doctorId, UUID sessionId, UUID enrollmentId) {
        IntegrationIdentity existing = confirmIntegrationSession(doctorId, sessionId, enrollmentId);
        requireCurrentIntegrationCertificate(existing);
        store.run(tx -> {
            Session current = tx.lockGet(SESSIONS, sessionId).orElse(null);
            if (current == null || !current.doctorId().equals(doctorId)
                    || !clock.instant().isBefore(current.expiresAt())
                    || !Hashing.constantTimeEquals(current.certificateFingerprint(), existing.certificateFingerprint())) {
                throw ApiException.forbidden("CERTIFICATE_SESSION_EXPIRED", "Certificate session expired");
            }
            tx.update(SESSIONS, new Session(current.sessionId(), current.tokenHash(), current.doctorId(),
                    current.tenantId(), current.identityMode(), current.expiresAt().plus(Duration.ofHours(1)),
                    current.certificateFingerprint(), current.authenticatedAt()));
            audit.record(current.tenantId(), current.doctorId().toString(), "CERTIFICATE_SESSION_EXTENDED", "DOCTOR",
                    doctorId.toString(), AuditService.SUCCESS, Map.of("certificateFingerprintSha256",
                            current.certificateFingerprint(), "sessionId", sessionId.toString()));
        });
        return confirmIntegrationSession(doctorId, sessionId, enrollmentId);
    }

    /** Revalidate external status and expiry before PACS report approval or connection extension. */
    public void requireCurrentIntegrationCertificate(IntegrationIdentity identity) {
        CertificateGate gate = certificateGate;
        if (gate == null) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_CHANGED", "Certificate enrollment is unavailable");
        }
        var current = gate.assertSigningAllowed(identity.doctorId());
        if (!current.registrationId().equals(identity.certificateEnrollmentId())
                || !Hashing.constantTimeEquals(current.fingerprintSha256(), identity.certificateFingerprint())) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_CHANGED", "Certificate enrollment changed");
        }
    }

    /**
     * The license number an administrator recorded, with license evidence, when pre-registering the doctor. For
     * integrations that cross-check their own account data; it is never an authenticator.
     */
    public Optional<String> registeredLicenseNumber(UUID doctorId) {
        return store.tx(tx -> tx.get(DOCTORS, doctorId).map(d -> d.licenseNumber));
    }

    // ------------------------------------------------------------------ certificate enrollment support

    /** Called only by the HMAC-authenticated PACS enrollment service; does not attest identity or license. */
    public DoctorRef selfEnrollFromPacs(String accountKey, String tenantId, String actorId, String name,
            String licenseNumber, String centerName) {
        String normalizedName = text(name, 2, 50, "이름은 2~50자여야 합니다.");
        String license = licenseNumber == null ? "" : licenseNumber.strip();
        if (!LICENSE.matcher(license).matches()) {
            throw invalid("면허번호는 숫자 1~20자리여야 합니다.");
        }
        String center = text(centerName, 2, 100, "소속 기관명은 2~100자여야 합니다.");
        return store.tx(tx -> {
            tx.lock("pacs-account|" + accountKey);
            Doctor existing = tx.lockFindOne(DOCTORS, "pacs_account_key", accountKey).orElse(null);
            if (existing != null) {
                if (existing.status.blocked()) throw notAllowed();
                if (!existing.tenantId.equals(tenantId) || !existing.licenseNumber.equals(license)
                        || !existing.name.equals(normalizedName) || !existing.centerName.equals(center)) {
                    throw ApiException.conflict("PACS_ACCOUNT_DETAILS_CHANGED", "등록된 PACS 계정 정보와 일치하지 않습니다.");
                }
                if (existing.status == DoctorStatus.ACTIVE) {
                    throw ApiException.conflict("PACS_ALREADY_ENROLLED", "공동인증서 등록이 완료된 계정입니다.");
                }
                return new DoctorRef(existing.id, existing.tenantId, existing.status, null);
            }
            tx.lock("doctor-license|" + tenantId + "|" + license);
            if (tx.find(DOCTORS, "tenant_id", tenantId).stream().anyMatch(d -> d.licenseNumber.equals(license))) {
                throw ApiException.conflict("DOCTOR_LICENSE_ALREADY_REGISTERED", "이미 등록된 면허번호입니다. 계정 연결을 확인해 주세요.");
            }
            if (tx.countAll(DOCTORS) >= policy().maxDoctors()) throw capacity();
            Doctor doctor = new Doctor(UUID.randomUUID(), tenantId, normalizedName, license, center, null,
                    null, null, clock.instant(), actorId);
            doctor.pacsAccountKey = accountKey;
            doctor.invitationState = InvitationState.VOID;
            tx.insert(DOCTORS, doctor);
            audit.record(tenantId, actorId, "DOCTOR_SELF_ENROLLMENT_STARTED", "DOCTOR", doctor.id.toString(),
                    AuditService.SUCCESS, Map.of("source", "PACS", "identityReview", "REQUIRED"));
            return new DoctorRef(doctor.id, doctor.tenantId, doctor.status, null);
        });
    }

    public DoctorRef pacsAccountDoctor(String accountKey) {
        return store.tx(tx -> tx.findOne(DOCTORS, "pacs_account_key", accountKey)
                .map(d -> new DoctorRef(d.id, d.tenantId, d.status, d.expectedCertificateFingerprint)).orElse(null));
    }

    public boolean requiresMedicalCredentials(UUID doctorId) {
        return store.tx(tx -> tx.get(DOCTORS, doctorId).map(d -> d.pacsAccountKey != null || d.selfRegisteredAccount).orElse(false));
    }

    public boolean isSelfRegisteredAccount(UUID doctorId) {
        return store.tx(tx -> tx.get(DOCTORS, doctorId).map(d -> d.selfRegisteredAccount).orElse(false));
    }

    /** Internal call from validated signup; no identity, license, role or PACS affiliation is attested here. */
    DoctorRef selfEnrollAccount(String tenant, String name, String license, String center) {
        return store.tx(tx -> {
            tx.lock("doctor-license|" + tenant + "|" + license);
            if (tx.find(DOCTORS, "tenant_id", tenant).stream().anyMatch(d -> d.licenseNumber.equals(license)))
                throw ApiException.conflict("DOCTOR_LICENSE_ALREADY_REGISTERED", "이미 등록된 면허번호입니다. 기존 계정을 확인해 주세요.");
            if (tx.countAll(DOCTORS) >= policy().maxDoctors()) throw capacity();
            Doctor d = new Doctor(UUID.randomUUID(), tenant, name, license, center, null, null, null,
                    clock.instant(), "SELF_REGISTRATION");
            d.selfRegisteredAccount = true;
            d.invitationState = InvitationState.VOID;
            tx.insert(DOCTORS, d);
            return new DoctorRef(d.id, d.tenantId, d.status, null);
        });
    }

    /** Resolves an invitation for a certificate challenge (PENDING doctors, or re-enrollment after reissue). */
    public CertificateInvitation claimInvitationForCertificate(String invitationCode, String source) {
        requireAdminInvitationsEnabled();
        return store.tx(tx -> {
            Instant now = clock.instant();
            prune(tx, now);
            String sourceKey = sourceKey(source);
            if (rateCount(tx, "failure", sourceKey, now) >= policy().sourceFailureHourlyLimit()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "INVITATION_ATTEMPTS_EXCEEDED",
                        "초대 코드 입력 실패가 너무 많습니다. 잠시 후 다시 시도해 주세요.", true);
            }
            requireChallengeQuota(tx, sourceKey, now);
            Doctor doctor = doctorByInvitation(tx, invitationCode);
            boolean usable = doctor != null && invitationUsableForCertificate(tx, doctor, now)
                    && doctor.invitationChallenges < policy().maxChallengesPerInvitation();
            if (!usable) {
                rateEvent(tx, "failure", sourceKey, now);
                throw invitationInvalid();
            }
            doctor.invitationChallenges++;
            tx.update(DOCTORS, doctor);
            rateEvent(tx, "challenge", sourceKey, now);
            return new CertificateInvitation(doctor.id, doctor.tenantId, doctor.invitationHash);
        });
    }

    /** Applies the per-source challenge quota to a certificate login challenge. */
    public void countLoginChallenge(String source) {
        store.run(tx -> {
            Instant now = clock.instant();
            prune(tx, now);
            String sourceKey = sourceKey(source);
            requireChallengeQuota(tx, sourceKey, now);
            rateEvent(tx, "challenge", sourceKey, now);
        });
    }

    /**
     * Completes a verified certificate proof: consumes the invitation (when one was used), moves a PENDING doctor to
     * IDENTITY_VERIFIED, records login evidence and issues a bearer session in the certificate identity mode.
     */
    public SessionResponse completeCertificateIdentity(CertificateIdentity identity) {
        if (identity.invitationHash() != null) requireAdminInvitationsEnabled();
        return store.tx(tx -> {
            Instant now = clock.instant();
            Doctor doctor = tx.lockGet(DOCTORS, identity.doctorId()).orElse(null);
            if (doctor == null) {
                throw invitationInvalid();
            }
            if (identity.invitationHash() != null) {
                if (doctor.invitationState == InvitationState.LOCKED) {
                    throw invitationLocked();
                }
                if (!identity.invitationHash().equals(doctor.invitationHash)
                        || !invitationUsableForCertificate(tx, doctor, now)) {
                    throw invitationInvalid();
                }
                doctor.invitationState = InvitationState.USED;
            }
            if (doctor.status.blocked()) {
                tx.update(DOCTORS, doctor);
                throw notAllowed();
            }
            if (doctor.status == DoctorStatus.PENDING) {
                doctor.status = DoctorStatus.IDENTITY_VERIFIED;
                doctor.identityMode = identity.identityMode();
                doctor.identityIssuer = identity.issuer();
                doctor.identitySubject = identity.subject();
                doctor.identityVerifiedAt = now;
            }
            tx.update(DOCTORS, doctor);
            addEvidence(tx, new DoctorEvidence.LoginEvidence(UUID.randomUUID(), doctor.id, doctor.tenantId,
                    identity.identityMode(), identity.issuer(), identity.subject(), identity.purpose(),
                    identity.challengeId(), now));
            audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_CERTIFICATE_AUTHENTICATED", "DOCTOR",
                    doctor.id.toString(), AuditService.SUCCESS,
                    Map.of("identityMode", identity.identityMode(), "purpose", identity.purpose()));
            return newSession(tx, doctor, identity.identityMode(), identity.subject(), now);
        });
    }

    /** Counts a failed certificate proof against the invitation; returns the lock error at the limit. */
    public ApiException recordCertificateFailure(UUID doctorId, String invitationHash, String reason) {
        if (doctorId == null || invitationHash == null) {
            return null;
        }
        return store.tx(tx -> {
            Doctor doctor = tx.lockGet(DOCTORS, doctorId).orElse(null);
            if (doctor == null || !invitationHash.equals(doctor.invitationHash)) {
                return null;
            }
            return countInvitationFailure(tx, doctor, "DOCTOR_CERTIFICATE_REJECTED", reason);
        });
    }

    /**
     * The physician's own consent for a certificate enrollment. Moves IDENTITY_VERIFIED → CONSENTED; for an already
     * consented/active physician (renewal) it records a new consent event without changing status.
     */
    public DoctorResponse consentForCertificate(DoctorPrincipal principal, ConsentRequest body) {
        return store.tx(tx -> {
            Doctor doctor = load(tx, principal);
            validateConsent(body);
            if (doctor.status == DoctorStatus.IDENTITY_VERIFIED) {
                return markConsented(tx, doctor, principal);
            }
            if (doctor.status == DoctorStatus.PENDING) {
                throw ApiException.conflict("INVALID_DOCTOR_STATE", "신원 확인 후에 동의할 수 있습니다.");
            }
            Instant now = clock.instant();
            addEvidence(tx, new DoctorEvidence.ConsentEvidence(UUID.randomUUID(), doctor.id, doctor.tenantId,
                    CONSENT_VERSION, principal.identityMode(), now));
            audit.record(doctor.tenantId, doctor.id.toString(), "DOCTOR_CERTIFICATE_CONSENTED", "DOCTOR",
                    doctor.id.toString(), AuditService.SUCCESS, Map.of("version", CONSENT_VERSION));
            return toResponse(doctor);
        });
    }

    public void invalidateSessions(UUID doctorId, String reason) {
        store.run(tx -> {
            int removed = tx.deleteWhere(SESSIONS, "doctor_id", doctorId.toString());
            tx.get(DOCTORS, doctorId).ifPresent(doctor -> audit.record(doctor.tenantId, "system",
                    "DOCTOR_SESSIONS_INVALIDATED", "DOCTOR", doctorId.toString(), AuditService.SUCCESS,
                    Map.of("reason", reason, "count", Integer.toString(removed))));
        });
    }

    public DoctorRef doctorRef(UUID id) {
        if (id == null) {
            return null;
        }
        return store.tx(tx -> tx.get(DOCTORS, id)
                .map(d -> new DoctorRef(d.id, d.tenantId, d.status, d.expectedCertificateFingerprint))
                .orElse(null));
    }

    public DoctorRef requireAdminDoctor(RequestActor admin, UUID id) {
        requireAdmin(admin);
        return store.tx(tx -> {
            Doctor doctor = findInTenant(tx, admin.tenantId(), id);
            return new DoctorRef(doctor.id, doctor.tenantId, doctor.status, doctor.expectedCertificateFingerprint);
        });
    }

    public DoctorResponse doctorResponse(UUID id) {
        return store.tx(tx -> tx.get(DOCTORS, id).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("DOCTOR_NOT_FOUND", "의사를 찾을 수 없습니다.")));
    }

    /** Marks an ISSUED invitation past its expiry EXPIRED (persisted by the caller's update). */
    private static boolean expireInvitation(Doctor doctor, Instant now) {
        if (doctor.invitationState == InvitationState.ISSUED && !now.isBefore(doctor.invitationExpiresAt)) {
            doctor.invitationState = InvitationState.EXPIRED;
            return true;
        }
        return false;
    }

    private boolean invitationUsableForCertificate(Tx tx, Doctor doctor, Instant now) {
        if (expireInvitation(doctor, now)) {
            tx.update(DOCTORS, doctor);
        }
        return doctor.invitationState == InvitationState.ISSUED && !doctor.status.blocked();
    }

    // ------------------------------------------------------------------ helpers

    private String issueInvitation(Doctor doctor, Instant now) {
        StringBuilder raw = new StringBuilder(25);
        for (int i = 0; i < 25; i++) {
            raw.append(INVITATION_ALPHABET.charAt(random.nextInt(INVITATION_ALPHABET.length())));
        }
        String normalized = raw.toString();
        doctor.invitationHash = Hashing.sha256Hex(normalized);
        doctor.invitationExpiresAt = now.plus(policy().invitationTtl());
        doctor.invitationState = InvitationState.ISSUED;
        doctor.invitationFailures = 0;
        doctor.invitationChallenges = 0;
        return String.join("-", normalized.substring(0, 5), normalized.substring(5, 10), normalized.substring(10, 15),
                normalized.substring(15, 20), normalized.substring(20, 25));
    }

    private boolean invitationUsable(Tx tx, Doctor doctor, Instant now) {
        if (expireInvitation(doctor, now)) {
            tx.update(DOCTORS, doctor);
        }
        return doctor.invitationState == InvitationState.ISSUED && doctor.status == DoctorStatus.PENDING;
    }

    /** The doctor holding this invitation code while it is ISSUED (row-locked), or null. */
    private static Doctor doctorByInvitation(Tx tx, String invitationCode) {
        String normalized = invitationCode == null ? null
                : invitationCode.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized == null || !INVITATION.matcher(normalized).matches()) {
            return null;
        }
        return tx.lockFindOne(DOCTORS, "invitation_hash", Hashing.sha256Hex(normalized)).orElse(null);
    }

    private ChallengeResponse newChallenge(Tx tx, Purpose purpose, UUID doctorId, String invitationHash,
            String sourceKey, Instant now) {
        requireCapacity(tx, now);
        byte[] nonceBytes = new byte[32];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        Instant expiresAt = now.plus(policy().challengeTtl());
        Challenge challenge = new Challenge(UUID.randomUUID(), purpose, nonce, doctorId, invitationHash, expiresAt,
                false, expiresAt.plus(policy().challengeTtl()));
        tx.insert(CHALLENGES, challenge);
        rateEvent(tx, "challenge", sourceKey, now);
        return new ChallengeResponse(challenge.id().toString(), nonce, challenge.expiresAt(), purpose.name());
    }

    private SessionResponse newSession(Tx tx, Doctor doctor, String identityMode, String certificateFingerprint,
            Instant now) {
        requireCapacity(tx, now);
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = now.plus(certificateFingerprint == null
                ? policy().sessionTtl() : Duration.ofHours(2));
        tx.insert(SESSIONS, new Session(UUID.randomUUID(), Hashing.sha256Hex(token), doctor.id, doctor.tenantId,
                identityMode, expiresAt, certificateFingerprint, now));
        return new SessionResponse(token, expiresAt, identityMode, toResponse(doctor));
    }

    private void requireChallengeQuota(Tx tx, String sourceKey, Instant now) {
        if (rateCount(tx, "challenge", sourceKey, now) >= policy().sourceChallengeHourlyLimit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHALLENGE_RATE_LIMITED",
                    "인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", true);
        }
    }

    /** Bounds live challenges + sessions (storage abuse guard shared by all instances). */
    private void requireCapacity(Tx tx, Instant now) {
        if (tx.countLive(CHALLENGES, now) + tx.countLive(SESSIONS, now) >= policy().maxTrackedEntries()) {
            throw capacity();
        }
    }

    private static String bucket(String kind, String sourceKey) {
        return Hashing.sha256Hex("doctor-" + kind + "|" + sourceKey);
    }

    private static long rateCount(Tx tx, String kind, String sourceKey, Instant now) {
        return tx.count(RATE_EVENTS, "bucket", bucket(kind, sourceKey), now);
    }

    private static void rateEvent(Tx tx, String kind, String sourceKey, Instant now) {
        tx.insert(RATE_EVENTS, new RateEvent(UUID.randomUUID(), bucket(kind, sourceKey), now, now.plus(RATE_WINDOW)));
    }

    private static void addEvidence(Tx tx, DoctorEvidence evidence) {
        tx.insert(EVIDENCE, StoredEvidence.of(evidence));
    }

    /** Removes a doctor's unconsumed challenges (consumed ones stay as replay markers until they expire). */
    private static void deleteOpenChallenges(Tx tx, UUID doctorId) {
        for (Challenge c : tx.find(CHALLENGES, "doctor_id", doctorId.toString())) {
            if (!c.used()) {
                tx.delete(CHALLENGES, c.id());
            }
        }
    }

    /** Deletes expired rows (garbage collection only: every check also compares expiry times itself). */
    private void prune(Tx tx, Instant now) {
        if (now.isBefore(lastPrune.plus(PRUNE_INTERVAL)) && !now.isBefore(lastPrune)) {
            return;
        }
        tx.deleteExpired(CHALLENGES, now);
        tx.deleteExpired(SESSIONS, now);
        tx.deleteExpired(RATE_EVENTS, now);
        lastPrune = now;
    }

    /** Row-locked doctor of this principal; blocked or foreign doctors are refused. */
    private static Doctor load(Tx tx, DoctorPrincipal principal) {
        Doctor doctor = tx.lockGet(DOCTORS, principal.doctorId()).orElse(null);
        requireUsable(doctor, principal);
        return doctor;
    }

    private static void requireUsable(Doctor doctor, DoctorPrincipal principal) {
        if (doctor == null || !doctor.tenantId.equals(principal.tenantId())) {
            throw sessionRequired();
        }
        if (doctor.status.blocked()) {
            throw notAllowed();
        }
    }

    private static Doctor findInTenant(Tx tx, String tenantId, UUID id) {
        Doctor doctor = tx.get(DOCTORS, id).orElse(null);
        if (doctor == null || !doctor.tenantId.equals(tenantId)) {
            throw ApiException.notFound("DOCTOR_NOT_FOUND", "의사를 찾을 수 없습니다.");
        }
        return doctor;
    }

    private static Doctor lockInTenant(Tx tx, String tenantId, UUID id) {
        Doctor doctor = tx.lockGet(DOCTORS, id).orElse(null);
        if (doctor == null || !doctor.tenantId.equals(tenantId)) {
            throw ApiException.notFound("DOCTOR_NOT_FOUND", "의사를 찾을 수 없습니다.");
        }
        return doctor;
    }

    private DoctorResponse toResponse(Doctor d) {
        Instant now = clock.instant();
        CertificateResponse certificate = null;
        if (d.credential != null) {
            String status = d.credentialRevoked || d.status == DoctorStatus.REVOKED ? "REVOKED"
                    : d.credential.withinValidity(now) ? "VALID" : "EXPIRED";
            certificate = CertificateResponse.of(d.credential, status);
        }
        InvitationState invitation = d.invitationState == InvitationState.ISSUED && d.invitationExpiresAt != null
                && !now.isBefore(d.invitationExpiresAt) ? InvitationState.EXPIRED : d.invitationState;
        return new DoctorResponse(d.id, d.tenantId, d.name, d.licenseNumber, d.centerName, d.googleEmail, d.status,
                d.identityMode, d.identityIssuer != null && VerifiedIdentity.GOOGLE.equals(d.identityMode),
                invitation == null ? null : invitation.name(), d.invitationExpiresAt,
                d.identityVerifiedAt, d.consentedAt, d.consentVersion, d.activatedAt, d.statusReason,
                d.statusChangedAt, d.createdAt, d.createdBy,
                d.credential != null ? d.credential.backend() : credentials.backend(), certificate,
                d.expectedCertificateFingerprint);
    }

    private static void requireAdmin(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_ROLE_REQUIRED", "관리자만 사용할 수 있는 기능입니다.");
        }
    }

    private static String text(String value, int min, int max, String message) {
        String text = value == null ? null : value.strip();
        if (text == null || text.chars().anyMatch(Character::isISOControl)) {
            throw invalid(message);
        }
        int length = text.codePointCount(0, text.length());
        if (length < min || length > max) {
            throw invalid(message);
        }
        return text;
    }

    private static String sourceKey(String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException activationError(RuntimeException e) {
        if (e instanceof CredentialException ce) {
            String detail = ce.getMessage();
            return switch (ce.kind()) {
                case NOT_CONFIGURED -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIGNING_BACKEND_NOT_CONFIGURED",
                        "서명 키 백엔드가 설정되지 않아 활성화할 수 없습니다: " + detail);
                case KEY_MISMATCH -> new ApiException(HttpStatus.BAD_GATEWAY, "CERTIFICATE_KEY_MISMATCH",
                        "발급된 인증서의 공개키가 서명 키와 일치하지 않아 활성화를 중단했습니다: " + detail);
                case KEY_POLICY_VIOLATION -> new ApiException(HttpStatus.BAD_GATEWAY, "SIGNING_KEY_POLICY_VIOLATION",
                        "서명 키가 정책(내보내기 불가 ECDSA P-256)에 맞지 않습니다: " + detail);
                case CERTIFICATE_NOT_VALID -> new ApiException(HttpStatus.BAD_GATEWAY, "CERTIFICATE_NOT_VALID",
                        "발급된 인증서가 유효하지 않습니다: " + detail);
                case UNAVAILABLE -> new ApiException(HttpStatus.BAD_GATEWAY, "SIGNING_BACKEND_UNAVAILABLE",
                        "서명 키 백엔드에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요: " + detail, true);
            };
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "SIGNING_BACKEND_UNAVAILABLE",
                "서명 키 백엔드 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", true);
    }

    private static ApiException signingUnavailable(CredentialException e) {
        if (e.kind() == CredentialException.Kind.NOT_CONFIGURED) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIGNING_BACKEND_NOT_CONFIGURED",
                    "서명 키 백엔드가 설정되지 않았습니다.");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "SIGNING_BACKEND_UNAVAILABLE",
                "서명 키 백엔드에서 서명하지 못했습니다: " + e.getMessage(), e.kind() == CredentialException.Kind.UNAVAILABLE);
    }

    private static ApiException certificateNotValid() {
        return ApiException.forbidden("CERTIFICATE_NOT_VALID", "인증서가 만료되었거나 폐지되어 서명할 수 없습니다.");
    }

    private static ApiException enrollmentChanged() {
        return ApiException.forbidden("CERTIFICATE_ENROLLMENT_CHANGED",
                "서명 중 인증서 등록 상태가 바뀌었거나 이 세션의 인증서가 활성 등록과 다릅니다. 다시 로그인해 주세요.");
    }

    private static ApiException sessionRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "DOCTOR_SESSION_REQUIRED", "의사 로그인이 필요하거나 세션이 만료되었습니다.");
    }

    private static ApiException notAllowed() {
        return ApiException.forbidden("DOCTOR_NOT_ALLOWED", "정지 또는 폐지된 의사 계정입니다.");
    }

    private static ApiException invitationInvalid() {
        return ApiException.unprocessable("INVITATION_INVALID",
                "초대 코드가 올바르지 않거나 만료되었거나 이미 사용되었습니다. 관리자에게 문의해 주세요.");
    }

    private static ApiException invitationLocked() {
        return new ApiException(HttpStatus.LOCKED, "INVITATION_LOCKED",
                "신원 확인 실패가 너무 많아 초대가 잠겼습니다. 관리자에게 초대 코드 재발급을 요청해 주세요.");
    }

    private static ApiException capacity() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DOCTOR_ACTIVATION_CAPACITY_EXCEEDED",
                "요청이 많아 잠시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("VALIDATION_FAILED", message);
    }
}
