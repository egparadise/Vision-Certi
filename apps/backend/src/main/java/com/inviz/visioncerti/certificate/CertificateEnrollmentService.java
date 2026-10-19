package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.certificate.CertificateDtos.CertificateSessionResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ConfigResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.EnrollmentResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.LoginRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileCallbackResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileConfig;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileStartResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.PcCertificateConfig;
import com.inviz.visioncerti.certificate.CertificateDtos.ReviewRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.VerifyRequest;
import com.inviz.visioncerti.certificate.CertificateEnrollment.Kind;
import com.inviz.visioncerti.certificate.CertificateEnrollment.RegistrationStatus;
import com.inviz.visioncerti.certificate.CertificateEnrollment.ReviewStatus;
import com.inviz.visioncerti.certificate.CertificateValidator.RevocationStatus;
import com.inviz.visioncerti.certificate.CertificateValidator.Validation;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.CertificateGate;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.SessionResponse;
import com.inviz.visioncerti.doctor.DoctorPrincipal;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.DoctorStatus;
import com.inviz.visioncerti.doctor.DoctorService.CertificateIdentity;
import com.inviz.visioncerti.doctor.DoctorService.CertificateInvitation;
import com.inviz.visioncerti.doctor.DoctorService.DoctorRef;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.store.Store;
import com.inviz.visioncerti.store.Tx;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Certificate enrollment, admin review, certificate login, unregister/renewal and the provider-neutral mobile flow.
 *
 * <p>State is durable ({@link Store}, shared with {@link DoctorService}): challenges, enrollments and mobile
 * transactions are rows. "One ACTIVE enrollment per doctor" and "one live enrollment per certificate fingerprint" are
 * UNIQUE constraints; binding a new proof takes advisory locks on the fingerprint and public key so concurrent
 * instances cannot bind the same certificate twice. Certificate path/CRL validation and the mobile provider run
 * outside storage transactions.
 */
public class CertificateEnrollmentService implements CertificateGate {

    public static final String PURPOSE_ENROLLMENT = "CERTIFICATE_ENROLLMENT";
    public static final String PURPOSE_LOGIN = "CERTIFICATE_LOGIN";
    public static final String PAYLOAD_HEADER = "vision-certi-certificate-auth-v1";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_TRACKED = 10_000;
    private static final Duration PRUNE_INTERVAL = Duration.ofSeconds(30);

    /**
     * Server-side copy of what the client must sign. {@code used} marks a consumed challenge; rows are kept until
     * {@code retainUntil} (expiry + one TTL) so replays get a precise error.
     */
    record Challenge(UUID id, String purpose, UUID doctorId, String tenantId, String invitationHash,
            byte[] payload, Instant issuedAt, Instant expiresAt, boolean used, Instant retainUntil) {
    }

    /** Mobile authentication transaction; only the SHA-256 of the client secret is stored. */
    record MobileTransaction(UUID id, String purpose, String nonce, String secretHash, UUID doctorId, String tenantId,
            String invitationHash, Instant createdAt, Instant expiresAt, Instant retainUntil, String providerSubject,
            String provider, boolean verified, boolean consumed) {

        MobileTransaction asVerified(String provider, String subject) {
            return new MobileTransaction(id, purpose, nonce, secretHash, doctorId, tenantId, invitationHash, createdAt,
                    expiresAt, retainUntil, subject, provider, true, consumed);
        }

        MobileTransaction asConsumed() {
            return new MobileTransaction(id, purpose, nonce, secretHash, doctorId, tenantId, invitationHash, createdAt,
                    expiresAt, retainUntil, providerSubject, provider, verified, true);
        }
    }

    static final com.inviz.visioncerti.store.Kind<Challenge> CHALLENGES =
            com.inviz.visioncerti.store.Kind.of("certificate_challenges", Challenge.class)
                    .id(Challenge::id).tenant(Challenge::tenantId).expiresAt(Challenge::retainUntil)
                    .column("purpose", Challenge::purpose)
                    .build();

    static final com.inviz.visioncerti.store.Kind<CertificateEnrollment> ENROLLMENTS =
            com.inviz.visioncerti.store.Kind.of("certificate_enrollments", CertificateEnrollment.class)
                    .id(e -> e.registrationId).tenant(e -> e.tenantId)
                    .column("doctor_id", e -> e.doctorId.toString())
                    .unique("live_fingerprint", e -> e.live() ? e.fingerprintSha256 : null,
                            () -> ApiException.conflict("CERTIFICATE_ALREADY_BOUND", "이 인증서는 이미 등록 처리 중입니다."))
                    .column("live_public_key", e -> e.live() ? e.publicKeySha256 : null)
                    .unique("active_doctor_id",
                            e -> e.registrationStatus == RegistrationStatus.ACTIVE ? e.doctorId.toString() : null,
                            () -> ApiException.conflict("INVALID_ENROLLMENT_STATE", "현재 인증서 등록 상태에서는 처리할 수 없습니다."))
                    .column("active_fingerprint",
                            e -> e.registrationStatus == RegistrationStatus.ACTIVE ? e.fingerprintSha256 : null)
                    .build();

    static final com.inviz.visioncerti.store.Kind<MobileTransaction> MOBILE =
            com.inviz.visioncerti.store.Kind.of("mobile_transactions", MobileTransaction.class)
                    .id(MobileTransaction::id).tenant(MobileTransaction::tenantId)
                    .expiresAt(MobileTransaction::retainUntil)
                    .column("purpose", MobileTransaction::purpose)
                    .build();

    /** Result of a successful proof, before it is stored. */
    private record Proof(Kind kind, String fingerprint, String publicKeySha256, String subjectDn, String issuerDn, String serial,
            Instant notBefore, Instant notAfter, String signatureAlgorithm, String keyAlgorithm, String certificateDer,
            List<String> chainDer, String provider, String providerSubject, RevocationStatus revocation,
            Instant checkedAt) {
    }

    private final DoctorService doctors;
    private final CertificateValidator validator;
    private final MobileIdentityProvider mobile;
    private final DevMockCertificateAuthority mockCa;
    private final AuditService audit;
    private final Store store;
    private final Clock clock;
    private final String serviceId;
    private final Duration challengeTtl;
    private final SecureRandom random = new SecureRandom();
    private volatile Instant lastPrune = Instant.EPOCH;

    public CertificateEnrollmentService(DoctorService doctors, CertificateValidator validator,
            MobileIdentityProvider mobile, DevMockCertificateAuthority mockCa, AuditService audit, Clock clock,
            String serviceId, Duration challengeTtl) {
        this.doctors = doctors;
        this.validator = validator;
        this.mobile = mobile;
        this.mockCa = mockCa;
        this.audit = audit;
        this.store = audit.store();
        this.clock = clock;
        this.serviceId = serviceId;
        this.challengeTtl = challengeTtl;
        doctors.attachCertificateGate(this);
    }

    public ConfigResponse config() {
        int anchors = validator.trust().trustAnchors().size();
        int crls = validator.trust().crls().size();
        return new ConfigResponse(doctors.activationPolicy(), serviceId, challengeTtl.toSeconds(),
                new PcCertificateConfig(anchors > 0, anchors, crls, CertificateValidator.ALLOWED_ALGORITHMS, "CRL",
                        false),
                new MobileConfig(mobile.configured(), mobile.configured() ? mobile.name() : null), mockCa != null,
                false, true);
    }

    public boolean devMockEnabled() {
        return mockCa != null;
    }

    // ------------------------------------------------------------------ challenges

    /** Enrollment challenge from an invitation code, or (principal != null) a renewal for the signed-in doctor. */
    public ChallengeResponse createEnrollmentChallenge(String invitationCode, DoctorPrincipal principal,
            String source) {
        if (principal == null) doctors.requireAdminInvitationsEnabled();
        requireTrust();
        UUID doctorId;
        String tenantId;
        String invitationHash = null;
        if (principal != null) {
            DoctorRef ref = doctors.doctorRef(principal.doctorId());
            if (ref == null || ref.status().blocked()) {
                throw ApiException.forbidden("DOCTOR_NOT_ALLOWED", "정지 또는 폐지된 의사 계정입니다.");
            }
            doctors.countLoginChallenge(source);
            doctorId = ref.id();
            tenantId = ref.tenantId();
        } else {
            CertificateInvitation invitation = doctors.claimInvitationForCertificate(invitationCode, source);
            doctorId = invitation.doctorId();
            tenantId = invitation.tenantId();
            invitationHash = invitation.invitationHash();
        }
        return newChallenge(PURPOSE_ENROLLMENT, doctorId, tenantId, invitationHash);
    }

    public ChallengeResponse createLoginChallenge(String source) {
        requireTrust();
        doctors.countLoginChallenge(source);
        return newChallenge(PURPOSE_LOGIN, null, null, null);
    }

    /** Internal entry point for an authenticated PACS account. Never exposed as a public doctor-id challenge. */
    public ChallengeResponse createPacsEnrollmentChallenge(DoctorRef ref, String source) {
        requireTrust();
        DoctorRef current = doctors.doctorRef(ref.id());
        if (current == null || !current.tenantId().equals(ref.tenantId()) || current.status().blocked()
                || current.status() == DoctorStatus.ACTIVE) {
            throw ApiException.forbidden("DOCTOR_NOT_ALLOWED", "인증서 최초 등록을 진행할 수 없는 계정입니다.");
        }
        doctors.countLoginChallenge(source);
        return newChallenge(PURPOSE_ENROLLMENT, current.id(), current.tenantId(), null);
    }

    private ChallengeResponse newChallenge(String purpose, UUID doctorId, String tenantId, String invitationHash) {
        return store.tx(tx -> {
            Instant now = clock.instant();
            prune(tx, now);
            if (tx.countLive(CHALLENGES, now) >= MAX_TRACKED) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CERTIFICATE_CAPACITY_EXCEEDED",
                        "요청이 많아 잠시 처리할 수 없습니다.", true);
            }
            UUID id = UUID.randomUUID();
            byte[] nonceBytes = new byte[32];
            random.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            Instant expiresAt = now.plus(challengeTtl);
            String payload = String.join("\n", PAYLOAD_HEADER,
                    "purpose=" + purpose,
                    "challengeId=" + id,
                    "serviceId=" + serviceId,
                    "doctorId=" + (doctorId == null ? "-" : doctorId),
                    "tenantId=" + (tenantId == null ? "-" : tenantId),
                    "nonce=" + nonce,
                    "issuedAt=" + now,
                    "expiresAt=" + expiresAt);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            tx.insert(CHALLENGES, new Challenge(id, purpose, doctorId, tenantId, invitationHash, bytes, now, expiresAt,
                    false, expiresAt.plus(challengeTtl)));
            return new ChallengeResponse(id, PURPOSE_ENROLLMENT.equals(purpose) ? id : null, purpose, doctorId,
                    tenantId, serviceId, nonce, now, expiresAt, payload, Base64.getEncoder().encodeToString(bytes),
                    CertificateValidator.ALLOWED_ALGORITHMS);
        });
    }

    /** Single use: any submission consumes the challenge (durably, before the proof is checked). */
    private Challenge claim(String challengeId, String expectedPurpose) {
        return store.tx(tx -> {
            Instant now = clock.instant();
            UUID id = parseUuid(challengeId);
            Challenge challenge = id == null ? null : tx.lockGet(CHALLENGES, id).orElse(null);
            boolean retained = challenge != null && now.isBefore(challenge.retainUntil());
            if (retained && challenge.used()) {
                throw ApiException.conflict("CHALLENGE_ALREADY_USED", "이미 사용된 인증 요청입니다. 처음부터 다시 시도해 주세요.");
            }
            if (!retained) {
                throw ApiException.notFound("CHALLENGE_NOT_FOUND", "인증 요청을 찾을 수 없습니다. 처음부터 다시 시도해 주세요.");
            }
            tx.update(CHALLENGES, new Challenge(challenge.id(), challenge.purpose(), challenge.doctorId(),
                    challenge.tenantId(), challenge.invitationHash(), challenge.payload(), challenge.issuedAt(),
                    challenge.expiresAt(), true, challenge.retainUntil()));
            if (!now.isBefore(challenge.expiresAt())) {
                throw new ApiException(HttpStatus.GONE, "CHALLENGE_EXPIRED", "인증 요청이 만료되었습니다. 처음부터 다시 시도해 주세요.");
            }
            if (!challenge.purpose().equals(expectedPurpose)) {
                throw ApiException.conflict("CHALLENGE_PURPOSE_MISMATCH", "이 인증 요청은 다른 용도로 발급되었습니다.");
            }
            return challenge;
        });
    }

    private Challenge peek(String challengeId) {
        UUID id = parseUuid(challengeId);
        Instant now = clock.instant();
        Challenge challenge = id == null ? null : store.tx(tx -> tx.get(CHALLENGES, id).orElse(null));
        if (challenge == null || challenge.used() || !now.isBefore(challenge.retainUntil())) {
            throw ApiException.notFound("CHALLENGE_NOT_FOUND", "인증 요청을 찾을 수 없습니다.");
        }
        return challenge;
    }

    // ------------------------------------------------------------------ PC enrollment

    public CertificateSessionResponse verifyEnrollment(VerifyRequest request) {
        if (request == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "요청 본문이 필요합니다.");
        }
        Challenge challenge = claim(request.registrationId(), PURPOSE_ENROLLMENT);
        Proof proof;
        try {
            proof = provePc(challenge, request.certificate(), request.chain(), request.signature(),
                    request.signatureAlgorithm());
        } catch (ApiException e) {
            ApiException locked = doctors.recordCertificateFailure(challenge.doctorId(), challenge.invitationHash(),
                    e.code());
            throw locked != null ? locked : e;
        }
        return bind(challenge.id(), challenge.doctorId(), challenge.tenantId(), challenge.invitationHash(), proof);
    }

    private Proof provePc(Challenge challenge, String certificate, List<String> chain, String signature,
            String algorithm) {
        X509Certificate leaf = validator.parse(certificate);
        List<X509Certificate> intermediates = validator.parseChain(chain);
        validator.checkLeafPolicy(leaf);
        validator.verifySignature(leaf, algorithm, challenge.payload(), signature);
        Validation validation = validator.validate(leaf, intermediates);
        CertificateValidator.requireGood(validation.revocation());
        List<String> chainDer = new ArrayList<>();
        try {
            for (X509Certificate c : intermediates) {
                chainDer.add(Base64.getEncoder().encodeToString(c.getEncoded()));
            }
            return new Proof(validation.mockAnchor() ? Kind.MOCK_CERTIFICATE : Kind.PC_CERTIFICATE,
                    CertificateValidator.fingerprint(leaf), CertificateValidator.publicKeySha256(leaf),
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(), leaf.getSerialNumber().toString(16),
                    leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant(), algorithm,
                    CertificateValidator.keyAlgorithm(leaf.getPublicKey()),
                    Base64.getEncoder().encodeToString(leaf.getEncoded()), chainDer, null, null,
                    validation.revocation(), validation.checkedAt());
        } catch (CertificateEncodingException e) {
            throw ApiException.unprocessable("CERTIFICATE_INVALID", "인증서를 인코딩할 수 없습니다.");
        }
    }

    /**
     * Stores a new PENDING enrollment for a verified proof and issues the physician session, in one transaction that
     * holds advisory locks on the certificate fingerprint and public key.
     */
    private CertificateSessionResponse bind(UUID registrationId, UUID doctorId, String tenantId, String invitationHash,
            Proof proof) {
        return store.tx(tx -> {
            DoctorRef ref = doctors.doctorRef(doctorId);
            if (ref == null) {
                throw ApiException.unprocessable("INVITATION_INVALID", "초대 코드가 올바르지 않거나 만료되었습니다.");
            }
            tx.lock("certificate-fingerprint|" + proof.fingerprint());
            if (proof.publicKeySha256() != null) {
                tx.lock("certificate-public-key|" + proof.publicKeySha256());
            }
            // A fresh proof allows a direct signup to resume its existing pending review after session expiry.
            // It never creates clinical access or resets consent/review evidence.
            CertificateEnrollment pending = tx.lockFindOne(ENROLLMENTS, "live_fingerprint", proof.fingerprint()).orElse(null);
            if (pending != null && pending.doctorId.equals(doctorId)
                    && pending.registrationStatus == RegistrationStatus.PENDING
                    && doctors.isSelfRegisteredAccount(doctorId)) {
                pending.externalStatus = proof.revocation();
                pending.externalCheckedAt = proof.checkedAt();
                tx.update(ENROLLMENTS, pending);
                SessionResponse resumed = doctors.completeCertificateIdentity(new CertificateIdentity(doctorId,
                        null, identityMode(proof.kind()), proof.issuerDn(), proof.fingerprint(),
                        PURPOSE_ENROLLMENT, registrationId));
                return new CertificateSessionResponse(pending.toResponse(), resumed.activationToken(), resumed.expiresAt(),
                        resumed.identityMode(), doctors.doctorResponse(doctorId));
            }
            requireUnbound(tx, proof.fingerprint(), proof.publicKeySha256(), doctorId);
            SessionResponse session = doctors.completeCertificateIdentity(new CertificateIdentity(doctorId,
                    invitationHash, identityMode(proof.kind()), proof.issuerDn(), proof.fingerprint(),
                    PURPOSE_ENROLLMENT, registrationId));
            EnrollmentResponse enrollment = store(tx, registrationId, doctorId, tenantId, proof,
                    ref.expectedCertificateFingerprint());
            return new CertificateSessionResponse(enrollment, session.activationToken(), session.expiresAt(),
                    session.identityMode(), doctors.doctorResponse(doctorId));
        });
    }

    private static void requireUnbound(Tx tx, String fingerprint, String publicKeySha256, UUID doctorId) {
        if (publicKeySha256 != null) {
            for (CertificateEnrollment e : tx.find(ENROLLMENTS, "live_public_key", publicKeySha256)) {
                if (!e.doctorId.equals(doctorId)) {
                    throw ApiException.conflict("CERTIFICATE_ALREADY_BOUND", "이 인증서의 키는 다른 의사 계정에 등록되어 있습니다.");
                }
            }
        }
        CertificateEnrollment same = tx.findOne(ENROLLMENTS, "live_fingerprint", fingerprint).orElse(null);
        if (same != null) {
            if (!same.doctorId.equals(doctorId)) {
                throw ApiException.conflict("CERTIFICATE_ALREADY_BOUND", "이 인증서는 다른 의사 계정에 등록되어 있습니다.");
            }
            throw ApiException.conflict("CERTIFICATE_ALREADY_ENROLLED", "이미 등록되었거나 검토 중인 인증서입니다.");
        }
    }

    private EnrollmentResponse store(Tx tx, UUID registrationId, UUID doctorId, String tenantId, Proof proof,
            String expectedFingerprint) {
        Instant now = clock.instant();
        CertificateEnrollment enrollment = new CertificateEnrollment(registrationId, doctorId, tenantId, proof.kind(),
                proof.fingerprint(), proof.publicKeySha256(), proof.subjectDn(), proof.issuerDn(), proof.serial(), proof.notBefore(),
                proof.notAfter(), proof.signatureAlgorithm(), proof.keyAlgorithm(), proof.certificateDer(),
                proof.chainDer(), proof.provider(), proof.providerSubject(), now);
        enrollment.externalStatus = proof.revocation();
        enrollment.externalCheckedAt = proof.checkedAt();
        enrollment.medicalCredentialsRequired = doctors.requiresMedicalCredentials(doctorId);
        if (!Boolean.TRUE.equals(enrollment.medicalCredentialsRequired)
                && expectedFingerprint != null && expectedFingerprint.equals(proof.fingerprint())) {
            enrollment.reviewStatus = ReviewStatus.APPROVED;
            enrollment.reviewMethod = "EXPECTED_FINGERPRINT";
            enrollment.reviewedBy = "PREREGISTRATION";
            enrollment.reviewedAt = now;
        }
        tx.insert(ENROLLMENTS, enrollment);
        audit.record(tenantId, doctorId.toString(), "CERTIFICATE_ENROLLMENT_SUBMITTED", "CERTIFICATE_ENROLLMENT",
                registrationId.toString(), AuditService.SUCCESS, Map.of("kind", proof.kind().name(),
                        "fingerprintSha256", proof.fingerprint(), "reviewStatus", enrollment.reviewStatus.name()));
        return enrollment.toResponse();
    }

    // ------------------------------------------------------------------ consent, review, activation

    public EnrollmentResponse submitMedicalCredentials(DoctorPrincipal principal, String registrationId,
            CertificateDtos.MedicalCredentialsRequest body) {
        return store.tx(tx -> {
            CertificateEnrollment e = own(tx, principal, parseUuid(registrationId));
            if (e.registrationStatus != RegistrationStatus.PENDING || e.reviewStatus != ReviewStatus.PENDING) {
                throw invalidState();
            }
            if (body == null || body.specialist() == null) {
                throw ApiException.unprocessable("MEDICAL_CREDENTIALS_INVALID", "의사면허번호와 전문의 여부를 입력해 주세요.");
            }
            String license = body.medicalLicenseNumber() == null ? "" : body.medicalLicenseNumber().strip();
            if (!license.matches("[0-9]{1,20}")
                    || !doctors.registeredLicenseNumber(e.doctorId).filter(license::equals).isPresent()) {
                throw ApiException.unprocessable("MEDICAL_LICENSE_MISMATCH", "등록 계정의 의사면허번호와 일치해야 합니다.");
            }
            String qualification = optionalMedicalText(body.specialistQualificationNumber(), 50);
            String specialty = optionalMedicalText(body.specialty(), 100);
            if (body.specialist() && (qualification == null || specialty == null)) {
                throw ApiException.unprocessable("SPECIALIST_CREDENTIALS_REQUIRED", "전문의 자격번호와 전문과목을 입력해 주세요.");
            }
            if (!body.specialist() && (qualification != null || specialty != null)) {
                throw ApiException.unprocessable("MEDICAL_CREDENTIALS_INVALID", "전문의가 아닌 경우 전문의 자격번호와 과목을 비워 주세요.");
            }
            e.medicalLicenseNumber = license;
            e.specialist = body.specialist();
            e.specialistQualificationNumber = qualification;
            e.specialty = specialty;
            Instant submitted = clock.instant();
            e.medicalCredentialsSubmittedAt = e.medicalCredentialsSubmittedAt != null
                    && !submitted.isAfter(e.medicalCredentialsSubmittedAt)
                    ? e.medicalCredentialsSubmittedAt.plusNanos(1) : submitted;
            tx.update(ENROLLMENTS, e);
            audit.record(e.tenantId, principal.doctorId().toString(), "MEDICAL_CREDENTIALS_SUBMITTED",
                    "CERTIFICATE_ENROLLMENT", e.registrationId.toString(), AuditService.SUCCESS,
                    Map.of("specialist", e.specialist.toString(), "verification", "PENDING_REVIEW"));
            return e.toResponse();
        });
    }

    private static String optionalMedicalText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.unprocessable("MEDICAL_CREDENTIALS_INVALID", "자격정보의 형식이나 길이를 확인해 주세요.");
        }
        return normalized;
    }

    private static void requireMedicalCredentials(CertificateEnrollment e) {
        if (Boolean.TRUE.equals(e.medicalCredentialsRequired)
                && (e.medicalCredentialsSubmittedAt == null || e.medicalLicenseNumber == null || e.specialist == null
                    || (e.specialist && (e.specialistQualificationNumber == null || e.specialty == null)))) {
            throw ApiException.conflict("MEDICAL_CREDENTIALS_REQUIRED", "의사면허 및 전문의 자격정보를 먼저 제출해 주세요.");
        }
    }

    private static void requireReviewedMedicalVersion(CertificateEnrollment e, ReviewRequest body) {
        requireMedicalCredentials(e);
        if (Boolean.TRUE.equals(e.medicalCredentialsRequired)
                && !e.medicalCredentialsSubmittedAt.equals(body.medicalCredentialsSubmittedAt())) {
            throw ApiException.conflict("MEDICAL_CREDENTIALS_CHANGED", "자격정보가 변경되었습니다. 최신 정보와 증빙을 다시 검토해 주세요.");
        }
    }

    public EnrollmentResponse consent(DoctorPrincipal principal, String registrationId, ConsentRequest body) {
        UUID id = parseUuid(registrationId);
        return store.tx(tx -> {
            CertificateEnrollment e = own(tx, principal, id);
            requireMedicalCredentials(e);
            if (e.registrationStatus != RegistrationStatus.PENDING || e.reviewStatus == ReviewStatus.REJECTED
                    || e.consentedAt != null) {
                throw invalidState();
            }
            doctors.consentForCertificate(principal, body);
            e.consentedAt = clock.instant();
            audit.record(e.tenantId, e.doctorId.toString(), "CERTIFICATE_ENROLLMENT_CONSENTED",
                    "CERTIFICATE_ENROLLMENT", e.registrationId.toString(), AuditService.SUCCESS, Map.of());
            maybeActivate(tx, e);
            tx.update(ENROLLMENTS, e);
            return e.toResponse();
        });
    }

    public EnrollmentResponse review(RequestActor admin, UUID doctorId, ReviewRequest body) {
        DoctorRef ref = doctors.requireAdminDoctor(admin, doctorId);
        if (body == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "요청 본문이 필요합니다.");
        }
        UUID id = parseUuid(body.registrationId());
        String decision = body.decision();
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "decision은 APPROVE 또는 REJECT여야 합니다.");
        }
        String evidenceRef = body.evidenceRef() == null ? null : body.evidenceRef().strip();
        if ("APPROVE".equals(decision) && (evidenceRef == null || evidenceRef.isEmpty() || evidenceRef.length() > 200)) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "승인에는 비교한 신원/면허 증빙 참조(evidenceRef)가 필요합니다.");
        }
        String note = body.note() == null ? null : body.note().strip();
        if (note != null && note.length() > 500) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "메모는 500자 이하여야 합니다.");
        }
        record Outcome(CertificateEnrollment snapshot, EnrollmentResponse rejected) {
        }
        Outcome first = store.tx(tx -> {
            CertificateEnrollment snapshot = enrollmentOf(tx, ref, id);
            if (snapshot.registrationStatus != RegistrationStatus.PENDING
                    || snapshot.reviewStatus != ReviewStatus.PENDING) {
                throw invalidState();
            }
            if ("REJECT".equals(decision)) {
                snapshot.reviewStatus = ReviewStatus.REJECTED;
                snapshot.reviewMethod = "ADMIN_REVIEW";
                snapshot.reviewedBy = admin.actorId();
                snapshot.reviewedAt = clock.instant();
                snapshot.reviewEvidenceRef = evidenceRef;
                snapshot.reviewNote = note;
                revoke(snapshot, "REJECTED_BY_REVIEW");
                tx.update(ENROLLMENTS, snapshot);
                auditReview(admin, snapshot, "REJECT");
                return new Outcome(snapshot, snapshot.toResponse());
            }
            requireReviewedMedicalVersion(snapshot, body);
            String fingerprint = body.fingerprintSha256() == null ? ""
                    : body.fingerprintSha256().replace(":", "").strip().toLowerCase(Locale.ROOT);
            if (!FINGERPRINT.matcher(fingerprint).matches()
                    || !Hashing.constantTimeEquals(fingerprint, snapshot.fingerprintSha256)) {
                throw ApiException.unprocessable("FINGERPRINT_MISMATCH",
                        "입력한 인증서 지문이 등록 요청의 지문과 일치하지 않습니다. 증빙과 다시 대조해 주세요.");
            }
            return new Outcome(snapshot, null);
        });
        if (first.rejected() != null) {
            return first.rejected();
        }
        RevocationStatus status = recheck(first.snapshot());
        return store.tx(tx -> {
            CertificateEnrollment e = enrollmentOf(tx, ref, id);
            e.externalStatus = status;
            e.externalCheckedAt = clock.instant();
            tx.update(ENROLLMENTS, e);
            CertificateValidator.requireGood(status);
            if (e.registrationStatus != RegistrationStatus.PENDING || e.reviewStatus != ReviewStatus.PENDING) {
                throw invalidState();
            }
            requireReviewedMedicalVersion(e, body);
            e.reviewStatus = ReviewStatus.APPROVED;
            e.reviewMethod = "ADMIN_REVIEW";
            e.reviewedBy = admin.actorId();
            e.reviewedAt = clock.instant();
            e.reviewEvidenceRef = evidenceRef;
            e.reviewNote = note;
            auditReview(admin, e, "APPROVE");
            maybeActivate(tx, e);
            tx.update(ENROLLMENTS, e);
            return e.toResponse();
        });
    }

    private void auditReview(RequestActor admin, CertificateEnrollment e, String decision) {
        Map<String, String> details = new HashMap<>();
        details.put("decision", decision);
        details.put("fingerprintSha256", e.fingerprintSha256);
        if (e.reviewEvidenceRef != null) {
            details.put("evidenceRef", e.reviewEvidenceRef);
        }
        if (e.medicalCredentialsSubmittedAt != null) {
            details.put("medicalCredentialsSubmittedAt", e.medicalCredentialsSubmittedAt.toString());
        }
        audit.record(e.tenantId, admin.actorId(), "CERTIFICATE_ENROLLMENT_REVIEWED", "CERTIFICATE_ENROLLMENT",
                e.registrationId.toString(), AuditService.SUCCESS, details);
    }

    /**
     * PENDING → ACTIVE only with both the doctor's consent and an approved review; supersedes (and persists) the
     * previous ACTIVE enrollment. The caller persists {@code e} afterwards.
     */
    private void maybeActivate(Tx tx, CertificateEnrollment e) {
        requireMedicalCredentials(e);
        if (e.registrationStatus != RegistrationStatus.PENDING || e.reviewStatus != ReviewStatus.APPROVED
                || e.consentedAt == null) {
            return;
        }
        Instant now = clock.instant();
        CertificateEnrollment previous = tx.lockFindOne(ENROLLMENTS, "active_doctor_id", e.doctorId.toString())
                .orElse(null);
        if (previous != null && previous.registrationStatus == RegistrationStatus.ACTIVE) {
            revoke(previous, "SUPERSEDED");
            tx.update(ENROLLMENTS, previous);
            e.supersedes = previous.registrationId;
        }
        e.registrationStatus = RegistrationStatus.ACTIVE;
        e.activatedAt = now;
        audit.record(e.tenantId, e.doctorId.toString(), "CERTIFICATE_ENROLLMENT_ACTIVATED", "CERTIFICATE_ENROLLMENT",
                e.registrationId.toString(), AuditService.SUCCESS, Map.of("fingerprintSha256", e.fingerprintSha256));
    }

    /** Marks {@code e} REVOKED; the caller persists it. */
    private void revoke(CertificateEnrollment e, String reason) {
        e.registrationStatus = RegistrationStatus.REVOKED;
        e.revokedAt = clock.instant();
        e.revocationReason = reason;
        audit.record(e.tenantId, "system", "CERTIFICATE_ENROLLMENT_REVOKED", "CERTIFICATE_ENROLLMENT",
                e.registrationId.toString(), AuditService.SUCCESS, Map.of("reason", reason));
    }

    public EnrollmentResponse unregister(DoctorPrincipal principal, String registrationId, String reason) {
        String why = reason == null || reason.isBlank() ? "UNREGISTERED" : "UNREGISTERED: " + reason.strip();
        if (why.length() > 200) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "사유는 200자 이하여야 합니다.");
        }
        EnrollmentResponse response = store.tx(tx -> {
            CertificateEnrollment e = own(tx, principal, parseUuid(registrationId));
            if (e.registrationStatus == RegistrationStatus.REVOKED) {
                throw invalidState();
            }
            revoke(e, why);
            tx.update(ENROLLMENTS, e);
            return e.toResponse();
        });
        doctors.invalidateSessions(principal.doctorId(), "CERTIFICATE_UNREGISTERED");
        return response;
    }

    /** Newest first (records are never removed). */
    public List<EnrollmentResponse> listForDoctor(DoctorPrincipal principal) {
        return history(principal.doctorId());
    }

    private List<EnrollmentResponse> history(UUID doctorId) {
        List<EnrollmentResponse> items = new ArrayList<>();
        store.tx(tx -> tx.find(ENROLLMENTS, "doctor_id", doctorId.toString()))
                .forEach(e -> items.add(0, e.toResponse()));
        return items;
    }

    public List<EnrollmentResponse> listForAdmin(RequestActor admin, UUID doctorId) {
        DoctorRef ref = doctors.requireAdminDoctor(admin, doctorId);
        return history(ref.id());
    }

    // ------------------------------------------------------------------ login

    public CertificateSessionResponse login(LoginRequest request) {
        if (request == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "요청 본문이 필요합니다.");
        }
        Challenge challenge = claim(request.challengeId(), PURPOSE_LOGIN);
        X509Certificate leaf = validator.parse(request.certificate());
        String fingerprint = CertificateValidator.fingerprint(leaf);
        String selected = request.fingerprintSha256() == null ? ""
                : request.fingerprintSha256().replace(":", "").strip().toLowerCase(Locale.ROOT);
        if (!fingerprint.equals(selected)) {
            throw ApiException.unprocessable("FINGERPRINT_MISMATCH", "선택한 인증서 지문이 제출한 인증서와 일치하지 않습니다.");
        }
        CertificateEnrollment active = activeByFingerprint(fingerprint, false);
        if (active != null) {
            long failures = audit.list(active.tenantId).stream()
                    .filter(e -> "CERTIFICATE_LOGIN_DENIED".equals(e.action())
                            && active.doctorId.toString().equals(e.targetId())
                            && fingerprint.equals(e.details().get("fingerprintSha256"))
                            && e.occurredAt().isAfter(clock.instant().minus(Duration.ofMinutes(10))))
                    .count();
            if (failures >= 10) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CERTIFICATE_LOGIN_LOCKED",
                        "Too many failed certificate login attempts; try again in 10 minutes", false);
            }
            audit.record(active.tenantId, active.doctorId.toString(), "CERTIFICATE_LOGIN_ATTEMPTED", "DOCTOR",
                    active.doctorId.toString(), AuditService.SUCCESS, Map.of("fingerprintSha256", fingerprint));
        }
        List<X509Certificate> intermediates = validator.parseChain(request.chain());
        validator.checkLeafPolicy(leaf);
        try {
            validator.verifySignature(leaf, request.signatureAlgorithm(), challenge.payload(), request.signature());
        } catch (RuntimeException error) {
            if (active != null) {
                audit.record(active.tenantId, active.doctorId.toString(), "CERTIFICATE_LOGIN_DENIED", "DOCTOR",
                        active.doctorId.toString(), AuditService.DENIED, Map.of("fingerprintSha256", fingerprint));
            }
            throw error;
        }
        if (active == null) {
            throw ApiException.forbidden("CERTIFICATE_NOT_ENROLLED", "활성 등록된 인증서가 아닙니다. 인증서 등록을 먼저 진행해 주세요.");
        }
        Validation validation = validator.validate(leaf, intermediates);
        recordExternal(active.registrationId, validation.revocation(), validation.checkedAt());
        CertificateValidator.requireGood(validation.revocation());
        CertificateSessionResponse result = session(active, challenge.id(), PURPOSE_LOGIN);
        audit.record(active.tenantId, active.doctorId.toString(), "CERTIFICATE_LOGIN_SUCCEEDED", "DOCTOR",
                active.doctorId.toString(), AuditService.SUCCESS, Map.of("fingerprintSha256", fingerprint));
        return result;
    }

    private CertificateEnrollment activeByFingerprint(String fingerprint, boolean mobileKind) {
        return store.tx(tx -> tx.find(ENROLLMENTS, "active_fingerprint", fingerprint).stream()
                .filter(e -> e.registrationStatus == RegistrationStatus.ACTIVE
                        && (e.kind == Kind.MOBILE_CERTIFICATE) == mobileKind)
                .findFirst().orElse(null));
    }

    private CertificateSessionResponse session(CertificateEnrollment active, UUID challengeId, String purpose) {
        return store.tx(tx -> {
            DoctorRef ref = doctors.doctorRef(active.doctorId);
            if (ref == null || ref.status().blocked()) {
                throw ApiException.forbidden("DOCTOR_NOT_ALLOWED", "정지 또는 폐지된 의사 계정입니다.");
            }
            CertificateEnrollment current = tx.lockGet(ENROLLMENTS, active.registrationId).orElse(null);
            if (current == null || current.registrationStatus != RegistrationStatus.ACTIVE) {
                // Revoked meanwhile (e.g. by the CRL check above): never issue the session.
                throw ApiException.forbidden("CERTIFICATE_NOT_ENROLLED", "활성 등록된 인증서가 아닙니다.");
            }
            SessionResponse session = doctors.completeCertificateIdentity(new CertificateIdentity(current.doctorId,
                    null, identityMode(current.kind), current.issuerDn, current.fingerprintSha256, purpose,
                    challengeId));
            return new CertificateSessionResponse(current.toResponse(), session.activationToken(),
                    session.expiresAt(), session.identityMode(), doctors.doctorResponse(current.doctorId));
        });
    }

    /** Persists the latest external status of an enrollment; an authentic REVOKED status revokes it. */
    private void recordExternal(UUID registrationId, RevocationStatus status, Instant at) {
        store.run(tx -> tx.lockGet(ENROLLMENTS, registrationId).ifPresent(e -> {
            e.externalStatus = status;
            e.externalCheckedAt = at;
            if (status == RevocationStatus.REVOKED && e.registrationStatus != RegistrationStatus.REVOKED) {
                revoke(e, "EXTERNAL_REVOKED");
            }
            tx.update(ENROLLMENTS, e);
        }));
    }

    // ------------------------------------------------------------------ gate used by DoctorService

    @Override
    public boolean hasActiveEnrollment(UUID doctorId) {
        return activeOf(doctorId) != null;
    }

    /** An interrupted PACS first registration remains resumable until the physician has consented. */
    public boolean hasConsentedPendingEnrollment(UUID doctorId) {
        return store.tx(tx -> tx.find(ENROLLMENTS, "doctor_id", doctorId.toString()).stream()
                .anyMatch(e -> e.registrationStatus == RegistrationStatus.PENDING && e.consentedAt != null));
    }

    private CertificateEnrollment activeOf(UUID doctorId) {
        return store.tx(tx -> tx.findOne(ENROLLMENTS, "active_doctor_id", doctorId.toString())
                .filter(e -> e.registrationStatus == RegistrationStatus.ACTIVE).orElse(null));
    }

    @Override
    public EnrollmentBinding assertSigningAllowed(UUID doctorId) {
        CertificateEnrollment active = activeOf(doctorId);
        if (active == null) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_REQUIRED", "활성 인증서 등록이 없어 서명할 수 없습니다.");
        }
        if (active.kind == Kind.MOBILE_CERTIFICATE) {
            return binding(active); // no external status source for mobile enrollments; internal ACTIVE governs
        }
        RevocationStatus status;
        try {
            status = recheck(active);
        } catch (ApiException e) {
            recordExternal(active.registrationId, RevocationStatus.UNKNOWN, clock.instant());
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_NOT_VALID",
                    "등록된 인증서를 지금 검증할 수 없어 서명할 수 없습니다: " + e.getMessage());
        }
        recordExternal(active.registrationId, status, clock.instant());
        if (status != RevocationStatus.GOOD) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_NOT_VALID",
                    "등록된 인증서 상태가 " + status + "여서 서명할 수 없습니다.");
        }
        return binding(active);
    }

    @Override
    public void confirmBinding(UUID doctorId, EnrollmentBinding binding) {
        CertificateEnrollment active = activeOf(doctorId);
        if (active == null || !active.registrationId.equals(binding.registrationId())
                || active.registrationStatus != RegistrationStatus.ACTIVE
                || !active.fingerprintSha256.equals(binding.fingerprintSha256())
                || (active.kind != Kind.MOBILE_CERTIFICATE && active.externalStatus != RevocationStatus.GOOD)) {
            throw ApiException.forbidden("CERTIFICATE_ENROLLMENT_CHANGED",
                    "서명 중 인증서 등록이 갱신·해지·폐지되어 서명 결과를 폐기했습니다.");
        }
    }

    private static EnrollmentBinding binding(CertificateEnrollment e) {
        return new EnrollmentBinding(e.registrationId, e.fingerprintSha256, e.kind.name());
    }

    /** Re-runs path + CRL validation of a stored PC/MOCK certificate. */
    private RevocationStatus recheck(CertificateEnrollment e) {
        if (e.kind == Kind.MOBILE_CERTIFICATE) {
            return RevocationStatus.GOOD;
        }
        X509Certificate leaf = CertificateValidator.parseDer(Base64.getDecoder().decode(e.certificateDer));
        List<X509Certificate> chain = new ArrayList<>();
        for (String der : e.chainDer) {
            chain.add(CertificateValidator.parseDer(Base64.getDecoder().decode(der)));
        }
        return validator.validate(leaf, chain).revocation();
    }

    // ------------------------------------------------------------------ mobile

    public MobileStartResponse startMobile(String purpose, String invitationCode, String source) {
        if (PURPOSE_ENROLLMENT.equals(purpose)) doctors.requireAdminInvitationsEnabled();
        requireMobile();
        if (!PURPOSE_ENROLLMENT.equals(purpose) && !PURPOSE_LOGIN.equals(purpose)) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "purpose는 CERTIFICATE_ENROLLMENT 또는 CERTIFICATE_LOGIN이어야 합니다.");
        }
        UUID doctorId = null;
        String tenantId = null;
        String invitationHash = null;
        if (PURPOSE_ENROLLMENT.equals(purpose)) {
            CertificateInvitation invitation = doctors.claimInvitationForCertificate(invitationCode, source);
            doctorId = invitation.doctorId();
            tenantId = invitation.tenantId();
            invitationHash = invitation.invitationHash();
        } else {
            doctors.countLoginChallenge(source);
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String transactionSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        byte[] nonceBytes = new byte[32];
        random.nextBytes(nonceBytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(challengeTtl);
        MobileTransaction t = new MobileTransaction(UUID.randomUUID(), purpose,
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes),
                Hashing.sha256Hex(transactionSecret), doctorId, tenantId, invitationHash, now, expiresAt,
                expiresAt.plus(challengeTtl), null, null, false, false);
        store.run(tx -> {
            prune(tx, now);
            if (tx.countLive(MOBILE, now) >= MAX_TRACKED) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CERTIFICATE_CAPACITY_EXCEEDED",
                        "요청이 많아 잠시 처리할 수 없습니다.", true);
            }
            tx.insert(MOBILE, t);
        });
        Map<String, Object> providerStart;
        try {
            providerStart = mobile.start(context(t));
        } catch (RuntimeException e) {
            store.run(tx -> tx.delete(MOBILE, t.id()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MOBILE_PROVIDER_UNAVAILABLE",
                    "모바일 인증 제공자에 연결하지 못했습니다.", true);
        }
        return new MobileStartResponse(t.id(), purpose, mobile.name(), t.expiresAt(), transactionSecret,
                providerStart);
    }

    public MobileCallbackResponse mobileCallback(String transactionId, Map<String, String> headers, byte[] body) {
        requireMobile();
        UUID id = parseUuid(transactionId);
        MobileTransaction t = id == null ? null : store.tx(tx -> tx.get(MOBILE, id).orElse(null));
        if (t == null || t.verified() || t.consumed() || !clock.instant().isBefore(t.expiresAt())) {
            throw callbackRejected();
        }
        MobileIdentityProvider.VerifiedMobileAuthentication verified;
        try {
            verified = mobile.verifyCallback(context(t), headers == null ? Map.of() : headers,
                    body == null ? new byte[0] : body);
        } catch (RuntimeException e) {
            audit.record(t.tenantId() == null ? "public" : t.tenantId(), "mobile-provider", "MOBILE_CALLBACK_REJECTED",
                    "MOBILE_TRANSACTION", t.id().toString(), AuditService.DENIED, Map.of());
            throw callbackRejected();
        }
        Instant now = clock.instant();
        if (verified == null || !t.id().equals(verified.transactionId()) || !t.purpose().equals(verified.purpose())
                || !Hashing.constantTimeEquals(t.nonce(), verified.nonce())
                || !mobile.name().equals(verified.provider())
                || verified.providerSubject() == null || verified.providerSubject().isBlank()
                || verified.providerSubject().length() > 255
                || verified.authenticatedAt() == null || verified.authenticatedAt().isBefore(t.createdAt())
                || !verified.authenticatedAt().isBefore(t.expiresAt()) || !now.isBefore(t.expiresAt())) {
            throw callbackRejected();
        }
        store.run(tx -> {
            MobileTransaction current = tx.lockGet(MOBILE, id).orElse(null);
            if (current == null || current.verified() || current.consumed()) {
                throw callbackRejected();
            }
            tx.update(MOBILE, current.asVerified(verified.provider(), verified.providerSubject()));
        });
        return new MobileCallbackResponse(t.id(), "VERIFIED");
    }

    public CertificateSessionResponse consumeMobile(String transactionId, String transactionSecret) {
        requireMobile();
        UUID id = parseUuid(transactionId);
        MobileTransaction t = store.tx(tx -> {
            MobileTransaction found = id == null ? null : tx.lockGet(MOBILE, id).orElse(null);
            if (found == null || transactionSecret == null
                    || !Hashing.constantTimeEquals(found.secretHash(), Hashing.sha256Hex(transactionSecret))) {
                throw ApiException.notFound("MOBILE_TRANSACTION_NOT_FOUND", "모바일 인증 거래를 찾을 수 없습니다.");
            }
            if (found.consumed()) {
                throw ApiException.conflict("MOBILE_TRANSACTION_ALREADY_USED", "이미 사용된 모바일 인증입니다.");
            }
            if (!clock.instant().isBefore(found.expiresAt())) {
                throw new ApiException(HttpStatus.GONE, "MOBILE_TRANSACTION_EXPIRED", "모바일 인증이 만료되었습니다.");
            }
            if (!found.verified()) {
                throw ApiException.conflict("MOBILE_TRANSACTION_NOT_VERIFIED", "모바일 인증이 아직 완료되지 않았습니다.");
            }
            MobileTransaction consumed = found.asConsumed();
            tx.update(MOBILE, consumed);
            return consumed;
        });
        String fingerprint = Hashing.sha256Hex("mobile|" + t.provider() + "|" + t.providerSubject());
        if (PURPOSE_ENROLLMENT.equals(t.purpose())) {
            Instant now = clock.instant();
            Proof proof = new Proof(Kind.MOBILE_CERTIFICATE, fingerprint, null, null, "mobile:" + t.provider(), null,
                    null, null, null, null, null, List.of(), t.provider(), t.providerSubject(), null, now);
            return bind(t.id(), t.doctorId(), t.tenantId(), t.invitationHash(), proof);
        }
        CertificateEnrollment active = activeByFingerprint(fingerprint, true);
        if (active == null) {
            throw ApiException.forbidden("CERTIFICATE_NOT_ENROLLED", "활성 등록된 모바일 인증이 아닙니다.");
        }
        return session(active, t.id(), PURPOSE_LOGIN);
    }

    private static MobileIdentityProvider.Transaction context(MobileTransaction t) {
        return new MobileIdentityProvider.Transaction(t.id(), t.purpose(), t.nonce(), t.createdAt(), t.expiresAt());
    }

    private void requireMobile() {
        if (!mobile.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MOBILE_PROVIDER_NOT_CONFIGURED",
                    "모바일 인증서 제공자가 설정되지 않았습니다.");
        }
    }

    private static ApiException callbackRejected() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "MOBILE_CALLBACK_REJECTED",
                "검증되지 않은 모바일 인증 결과는 받아들이지 않습니다.");
    }

    // ------------------------------------------------------------------ dev mock

    /** Signs a stored challenge with an in-memory MOCK_CERTIFICATE and runs the normal verify or login pipeline. */
    public CertificateSessionResponse devMockSign(String challengeId, String fingerprint) {
        if (mockCa == null) {
            throw ApiException.notFound("DEV_MOCK_CERTIFICATE_DISABLED", "개발용 모의 인증서가 비활성화되어 있습니다.");
        }
        Challenge challenge = peek(challengeId);
        X509Certificate certificate;
        if (PURPOSE_ENROLLMENT.equals(challenge.purpose())) {
            certificate = mockCa.certificateFor(challenge.doctorId());
        } else {
            String fp = fingerprint == null ? "" : fingerprint.strip().toLowerCase(Locale.ROOT);
            certificate = mockCa.certificateByFingerprint(fp);
            if (certificate == null) {
                throw ApiException.forbidden("CERTIFICATE_NOT_ENROLLED", "알 수 없는 모의 인증서입니다.");
            }
        }
        String fp = CertificateValidator.fingerprint(certificate);
        String signature = Base64.getEncoder().encodeToString(mockCa.sign(fp, challenge.payload()));
        String der;
        try {
            der = Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
        if (PURPOSE_ENROLLMENT.equals(challenge.purpose())) {
            return verifyEnrollment(new VerifyRequest(challenge.id().toString(), der, List.of(), signature,
                    CertificateValidator.SHA256_WITH_ECDSA));
        }
        return login(new LoginRequest(challenge.id().toString(), fp, der, List.of(), signature,
                CertificateValidator.SHA256_WITH_ECDSA));
    }

    // ------------------------------------------------------------------ helpers

    private void requireTrust() {
        if (!validator.trustConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICATE_TRUST_NOT_CONFIGURED",
                    "인증서 신뢰 기관(VISIONCERTI_CERT_TRUST_ANCHORS_FILE)이 설정되지 않았습니다.");
        }
    }

    private static CertificateEnrollment own(Tx tx, DoctorPrincipal principal, UUID id) {
        CertificateEnrollment e = id == null ? null : tx.lockGet(ENROLLMENTS, id).orElse(null);
        if (e == null || !e.doctorId.equals(principal.doctorId()) || !e.tenantId.equals(principal.tenantId())) {
            throw ApiException.notFound("ENROLLMENT_NOT_FOUND", "인증서 등록을 찾을 수 없습니다.");
        }
        return e;
    }

    private static CertificateEnrollment enrollmentOf(Tx tx, DoctorRef ref, UUID id) {
        CertificateEnrollment e = id == null ? null : tx.lockGet(ENROLLMENTS, id).orElse(null);
        if (e == null || !e.doctorId.equals(ref.id()) || !e.tenantId.equals(ref.tenantId())) {
            throw ApiException.notFound("ENROLLMENT_NOT_FOUND", "인증서 등록을 찾을 수 없습니다.");
        }
        return e;
    }

    private static ApiException invalidState() {
        return ApiException.conflict("INVALID_ENROLLMENT_STATE", "현재 인증서 등록 상태에서는 처리할 수 없습니다.");
    }

    private static String identityMode(Kind kind) {
        return kind.name();
    }

    /** Garbage collection of expired rows only; every check compares expiry times itself. */
    private void prune(Tx tx, Instant now) {
        if (now.isBefore(lastPrune.plus(PRUNE_INTERVAL)) && !now.isBefore(lastPrune)) {
            return;
        }
        tx.deleteExpired(CHALLENGES, now);
        tx.deleteExpired(MOBILE, now);
        lastPrune = now;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Test/diagnostic view of a doctor's ACTIVE enrollment id. */
    public UUID activeEnrollmentId(UUID doctorId) {
        CertificateEnrollment active = activeOf(doctorId);
        return active == null ? null : active.registrationId;
    }
}
