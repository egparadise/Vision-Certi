package com.inviz.visioncerti.enrollment;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.enrollment.Account.AccountType;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.Consents;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.CreatePhoneChallenge;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.PhoneChallengeResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.PhoneVerificationResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.RegistrationRequest;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.RegistrationResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.VerifyPhoneChallenge;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Self-service signup with a mandatory phone one-time code. All state is in memory and guarded by this
 * object's monitor; only password hashing runs outside the lock. Error messages never contain submitted values.
 */
@Service
public class EnrollmentService {

    public static final String CONSENT_VERSION = "2026-10-13-draft";

    private static final Set<String> RESERVED_USERNAMES = Set.of("doctor-demo", "admin-demo");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{3,31}");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    /** Expired challenges are kept a little longer so late attempts get "expired" rather than "not found". */
    private static final Duration CHALLENGE_RETENTION = Duration.ofMinutes(10);

    private final SmsSender sms;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final EnrollmentPolicy policy;
    private final SecureRandom random = new SecureRandom();
    private final byte[] codeKey = new byte[32];

    private final Map<UUID, Challenge> challenges = new HashMap<>();
    /** Unused verification tokens keyed by SHA-256 of the token; consumed tokens are removed. */
    private final Map<String, Grant> grants = new HashMap<>();
    private final Map<String, Deque<Instant>> sendsByPhone = new HashMap<>();
    private final Map<String, Deque<Instant>> sendsBySource = new HashMap<>();
    /** Keyed by lower-case username. */
    private final Map<String, Account> accounts = new HashMap<>();

    public EnrollmentService(SmsSender sms, PasswordHasher hasher, Clock clock, EnrollmentPolicy policy) {
        this.sms = sms;
        this.hasher = hasher;
        this.clock = clock;
        this.policy = policy;
        random.nextBytes(codeKey);
    }

    // ---------------------------------------------------------------- phone challenge

    public PhoneChallengeResponse createChallenge(CreatePhoneChallenge body, String source) {
        requireSmsProvider();
        String phone = PhoneNumbers.normalize(body == null ? null : body.phone());
        if (phone == null) {
            throw invalid("휴대폰 번호는 010으로 시작하는 11자리 숫자여야 합니다.");
        }
        String sourceKey = source == null || source.isBlank() ? "unknown" : source;
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Challenge challenge;
        synchronized (this) {
            Instant now = clock.instant();
            prune(now);
            Deque<Instant> phoneSends = sendsByPhone.get(phone);
            if (phoneSends != null && !phoneSends.isEmpty()) {
                Instant resendAt = phoneSends.peekLast().plus(policy.resendCooldown());
                if (now.isBefore(resendAt)) {
                    long seconds = Math.max(1, Duration.between(now, resendAt).toSeconds());
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PHONE_CHALLENGE_COOLDOWN",
                            "인증번호는 " + seconds + "초 후에 다시 요청할 수 있습니다.", true);
                }
            }
            Deque<Instant> sourceSends = sendsBySource.get(sourceKey);
            if (size(phoneSends) >= policy.phoneHourlyLimit() || size(sourceSends) >= policy.sourceHourlyLimit()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PHONE_CHALLENGE_RATE_LIMITED",
                        "인증번호 요청 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요.", true);
            }
            requireCapacity(challenges.size() + sendsByPhone.size() + sendsBySource.size());

            // A new code replaces every earlier challenge and every unused verification token for the phone.
            for (Challenge c : challenges.values()) {
                if (c.phone.equals(phone) && c.state == ChallengeState.PENDING) {
                    c.state = ChallengeState.SUPERSEDED;
                }
            }
            grants.values().removeIf(g -> g.phone.equals(phone));

            UUID id = UUID.randomUUID();
            challenge = new Challenge(id, phone, codeDigest(id, code), now, now.plus(policy.challengeTtl()));
            challenges.put(id, challenge);
            sendsByPhone.computeIfAbsent(phone, k -> new ArrayDeque<>()).addLast(now);
            sendsBySource.computeIfAbsent(sourceKey, k -> new ArrayDeque<>()).addLast(now);
        }
        try {
            sms.send(phone, "[Vision-Certi] 인증번호 " + code + " (3분 이내 입력)");
        } catch (RuntimeException e) {
            synchronized (this) {
                challenge.state = ChallengeState.SUPERSEDED;
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_SEND_FAILED",
                    "인증번호를 발송하지 못했습니다. 잠시 후 다시 시도해 주세요.", true);
        }
        return new PhoneChallengeResponse(challenge.id.toString(), challenge.expiresAt,
                challenge.createdAt.plus(policy.resendCooldown()), sms.mode(),
                sms.revealsCodeToClient() ? code : null);
    }

    public PhoneVerificationResponse verify(String challengeId, VerifyPhoneChallenge body) {
        requireSmsProvider();
        String code = body == null ? null : body.code();
        if (code == null || !CODE.matcher(code).matches()) {
            throw invalid("인증번호는 숫자 6자리여야 합니다.");
        }
        UUID id = parseUuid(challengeId);
        synchronized (this) {
            Instant now = clock.instant();
            prune(now);
            Challenge c = id == null ? null : challenges.get(id);
            if (c == null) {
                throw ApiException.notFound("PHONE_CHALLENGE_NOT_FOUND", "인증 요청을 찾을 수 없습니다. 인증번호를 다시 요청해 주세요.");
            }
            switch (c.state) {
                case VERIFIED -> throw ApiException.conflict("PHONE_CHALLENGE_ALREADY_VERIFIED",
                        "이미 완료된 인증 요청입니다. 인증번호를 다시 요청해 주세요.");
                case SUPERSEDED -> throw ApiException.conflict("PHONE_CHALLENGE_SUPERSEDED",
                        "새 인증번호가 발송되어 이전 인증 요청은 사용할 수 없습니다.");
                case LOCKED -> throw locked();
                case PENDING -> {
                }
            }
            if (!now.isBefore(c.expiresAt)) {
                throw new ApiException(HttpStatus.GONE, "PHONE_CHALLENGE_EXPIRED",
                        "인증번호 유효 시간이 지났습니다. 인증번호를 다시 요청해 주세요.");
            }
            if (!MessageDigest.isEqual(codeDigest(c.id, code), c.codeDigest)) {
                c.attempts++;
                if (c.attempts >= policy.maxVerifyAttempts()) {
                    c.state = ChallengeState.LOCKED;
                    throw locked();
                }
                throw ApiException.unprocessable("PHONE_CODE_MISMATCH",
                        "인증번호가 일치하지 않습니다. 남은 시도 횟수: " + (policy.maxVerifyAttempts() - c.attempts) + "회");
            }
            requireCapacity(grants.size());
            c.state = ChallengeState.VERIFIED;
            byte[] tokenBytes = new byte[32];
            random.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            Instant expiresAt = now.plus(policy.verificationTokenTtl());
            grants.put(Hashing.sha256Hex(token), new Grant(c.phone, c.id, now, expiresAt));
            return new PhoneVerificationResponse(token, expiresAt, sms.mode());
        }
    }

    // ---------------------------------------------------------------- registration

    public RegistrationResponse register(RegistrationRequest request) {
        requireSmsProvider();
        Validated v = validate(request);
        String tokenKey = Hashing.sha256Hex(request.verificationToken());
        synchronized (this) {
            Instant now = clock.instant();
            prune(now);
            requireGrant(tokenKey, v.phone, now);
            requireUsernameAvailable(v.usernameKey);
            if (accounts.size() >= policy.maxAccounts()) {
                throw capacityExceeded();
            }
        }
        // Expensive; runs outside the lock only after the proof and username were checked.
        PasswordHasher.PasswordHash passwordHash = hasher.hash(request.password());
        synchronized (this) {
            Instant now = clock.instant();
            Grant grant = requireGrant(tokenKey, v.phone, now);
            requireUsernameAvailable(v.usernameKey);
            Account account = new Account(UUID.randomUUID(), v.username, v.accountType, Account.Status.REGISTERED,
                    v.name, v.phone,
                    v.email, v.institution, v.licenseNumber, v.specialty, passwordHash,
                    new Account.ConsentRecord(CONSENT_VERSION, true, true, true, now),
                    new Account.PhoneVerification(grant.challengeId, sms.verificationLabel(), grant.verifiedAt), now);
            // Consume the proof and claim the username in the same critical section.
            grants.remove(tokenKey);
            accounts.put(v.usernameKey, account);
            return new RegistrationResponse(account.registrationId(), account.username(),
                    account.accountType().name(), account.status().name(), sms.verificationLabel(), now);
        }
    }

    /** Read access for tests and future admin review; returns empty for unknown names. */
    /**
     * Personal signup depends on proving control of a phone number. Without a real SMS provider (only the dev
     * {@link MockSmsSender} exists) every signup route answers 503 {@code SMS_NOT_CONFIGURED}.
     */
    private void requireSmsProvider() {
        if (!sms.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_NOT_CONFIGURED",
                    "휴대폰 인증(SMS) 제공자가 설정되지 않아 개인 가입을 사용할 수 없습니다.");
        }
    }

    public synchronized Optional<Account> findByUsername(String username) {
        return username == null ? Optional.empty()
                : Optional.ofNullable(accounts.get(username.toLowerCase(Locale.ROOT)));
    }

    private Grant requireGrant(String tokenKey, String phone, Instant now) {
        Grant grant = grants.get(tokenKey);
        if (grant == null) {
            throw ApiException.unprocessable("PHONE_VERIFICATION_INVALID",
                    "휴대폰 인증이 유효하지 않거나 이미 사용되었습니다. 휴대폰 인증을 다시 진행해 주세요.");
        }
        if (!now.isBefore(grant.expiresAt)) {
            grants.remove(tokenKey);
            throw ApiException.unprocessable("PHONE_VERIFICATION_EXPIRED",
                    "휴대폰 인증 유효 시간이 지났습니다. 휴대폰 인증을 다시 진행해 주세요.");
        }
        if (!grant.phone.equals(phone)) {
            throw ApiException.unprocessable("PHONE_VERIFICATION_MISMATCH", "인증한 휴대폰 번호와 입력한 번호가 다릅니다.");
        }
        return grant;
    }

    private void requireUsernameAvailable(String usernameKey) {
        if (accounts.containsKey(usernameKey)) {
            throw ApiException.conflict("USERNAME_UNAVAILABLE", "이미 사용 중이거나 사용할 수 없는 아이디입니다.");
        }
    }

    private Validated validate(RegistrationRequest r) {
        if (r == null) {
            throw invalid("요청 본문이 필요합니다.");
        }
        if ("CLINICIAN".equals(r.accountType())) {
            // Physicians are preregistered by an admin and activate with an invitation (docs/DOCTOR_ACTIVATION.md).
            throw ApiException.unprocessable("CLINICIAN_SELF_REGISTRATION_DISABLED",
                    "의사 회원은 직접 가입할 수 없습니다. 관리자 사전등록 후 받은 초대 코드로 활성화해 주세요.");
        }
        if (!"PERSONAL".equals(r.accountType())) {
            throw invalid("회원 유형은 PERSONAL이어야 합니다.");
        }
        AccountType type = AccountType.PERSONAL;

        String username = r.username();
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw invalid("아이디는 영문자로 시작하는 4~32자의 영문, 숫자, 밑줄(_), 마침표(.), 하이픈(-)이어야 합니다.");
        }
        String usernameKey = username.toLowerCase(Locale.ROOT);
        if (RESERVED_USERNAMES.contains(usernameKey)) {
            throw ApiException.conflict("USERNAME_UNAVAILABLE", "이미 사용 중이거나 사용할 수 없는 아이디입니다.");
        }

        String password = r.password();
        int passwordLength = password == null ? 0 : password.codePointCount(0, password.length());
        if (passwordLength < 12 || passwordLength > 128) {
            throw invalid("비밀번호는 12자 이상 128자 이하여야 합니다.");
        }

        String name = requireText(r.name(), 2, 50, "이름은 2자 이상 50자 이하여야 합니다.");

        String phone = PhoneNumbers.normalize(r.phone());
        if (phone == null) {
            throw invalid("휴대폰 번호는 010으로 시작하는 11자리 숫자여야 합니다.");
        }

        String email = blankToNull(r.email());
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
            throw invalid("이메일 형식이 올바르지 않습니다.");
        }

        String institution = blankToNull(r.institution());
        String licenseNumber = blankToNull(r.licenseNumber());
        String specialty = blankToNull(r.specialty());
        if (institution != null || licenseNumber != null || specialty != null) {
            throw invalid("개인 회원은 소속 기관, 면허번호, 전문 분야를 입력할 수 없습니다.");
        }

        Consents consents = r.consents();
        if (consents == null || !Boolean.TRUE.equals(consents.authentication())
                || !Boolean.TRUE.equals(consents.signature()) || !Boolean.TRUE.equals(consents.privacy())) {
            throw ApiException.unprocessable("CONSENT_REQUIRED", "필수 약관(인증, 서명, 개인정보 처리)에 모두 동의해야 합니다.");
        }
        if (!CONSENT_VERSION.equals(consents.version())) {
            throw ApiException.unprocessable("CONSENT_VERSION_MISMATCH", "약관 버전이 최신이 아닙니다. 화면을 새로고침한 뒤 다시 동의해 주세요.");
        }

        String token = r.verificationToken();
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw ApiException.unprocessable("PHONE_VERIFICATION_REQUIRED", "휴대폰 인증을 먼저 완료해 주세요.");
        }
        return new Validated(type, username, usernameKey, name, phone, email, institution, licenseNumber, specialty);
    }

    private record Validated(AccountType accountType, String username, String usernameKey, String name, String phone,
            String email, String institution, String licenseNumber, String specialty) {
    }

    private static String requireText(String value, int min, int max, String message) {
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ---------------------------------------------------------------- housekeeping

    /** Drops expired state so memory stays bounded by the rate limits and {@link EnrollmentPolicy#maxTrackedEntries}. */
    private void prune(Instant now) {
        challenges.values().removeIf(c -> !now.isBefore(c.expiresAt.plus(CHALLENGE_RETENTION)));
        grants.values().removeIf(g -> !now.isBefore(g.expiresAt));
        pruneWindow(sendsByPhone, now);
        pruneWindow(sendsBySource, now);
    }

    private static void pruneWindow(Map<String, Deque<Instant>> sends, Instant now) {
        Instant cutoff = now.minus(RATE_WINDOW);
        Iterator<Deque<Instant>> it = sends.values().iterator();
        while (it.hasNext()) {
            Deque<Instant> deque = it.next();
            while (!deque.isEmpty() && !deque.peekFirst().isAfter(cutoff)) {
                deque.pollFirst();
            }
            if (deque.isEmpty()) {
                it.remove();
            }
        }
    }

    private void requireCapacity(int tracked) {
        if (tracked >= policy.maxTrackedEntries()) {
            throw capacityExceeded();
        }
    }

    private static ApiException capacityExceeded() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ENROLLMENT_CAPACITY_EXCEEDED",
                "가입 요청이 많아 잠시 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", true);
    }

    private static int size(Deque<Instant> deque) {
        return deque == null ? 0 : deque.size();
    }

    private byte[] codeDigest(UUID challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeKey, "HmacSHA256"));
            return mac.doFinal((challengeId + "|" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("VALIDATION_FAILED", message);
    }

    private ApiException locked() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PHONE_CHALLENGE_LOCKED",
                "인증번호를 " + policy.maxVerifyAttempts() + "회 잘못 입력했습니다. 인증번호를 다시 요청해 주세요.", false);
    }

    private enum ChallengeState { PENDING, VERIFIED, SUPERSEDED, LOCKED }

    private static final class Challenge {
        final UUID id;
        final String phone;
        final byte[] codeDigest;
        final Instant createdAt;
        final Instant expiresAt;
        int attempts;
        ChallengeState state = ChallengeState.PENDING;

        Challenge(UUID id, String phone, byte[] codeDigest, Instant createdAt, Instant expiresAt) {
            this.id = id;
            this.phone = phone;
            this.codeDigest = codeDigest;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private record Grant(String phone, UUID challengeId, Instant verifiedAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "Grant[<redacted>]";
        }
    }
}
