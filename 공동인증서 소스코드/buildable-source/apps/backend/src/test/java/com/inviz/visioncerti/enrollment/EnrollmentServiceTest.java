package com.inviz.visioncerti.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.Consents;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.CreatePhoneChallenge;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.PhoneChallengeResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.PhoneVerificationResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.RegistrationRequest;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.RegistrationResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.VerifyPhoneChallenge;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Service rules with a controllable clock. Codes come from the dev mock sender's {@code devCode}. */
class EnrollmentServiceTest {

    private static final String PHONE = "010-1234-5678";
    private static final String NORMALIZED = "01012345678";
    private static final String PASSWORD = "correct horse battery";

    private MutableClock clock;
    private PasswordHasher hasher;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
        hasher = new PasswordHasher();
        service = new EnrollmentService(new MockSmsSender(), hasher, clock, EnrollmentPolicy.defaults());
    }

    private PhoneChallengeResponse challenge(String phone) {
        return service.createChallenge(new CreatePhoneChallenge(phone), "10.0.0.1");
    }

    private PhoneVerificationResponse verify(PhoneChallengeResponse c) {
        return service.verify(c.challengeId(), new VerifyPhoneChallenge(c.devCode()));
    }

    private String token(String phone) {
        return verify(challenge(phone)).verificationToken();
    }

    private static RegistrationRequest personal(String username, String phone, String token) {
        return new RegistrationRequest("PERSONAL", username, PASSWORD, "홍길동", phone, null, null, null, null, token,
                new Consents(true, true, true, EnrollmentService.CONSENT_VERSION));
    }

    private static String code(Throwable t) {
        return ((ApiException) t).code();
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    void challengeNormalizesPhoneAndReturnsMockCode() {
        PhoneChallengeResponse c = challenge(PHONE);
        assertThat(c.mode()).isEqualTo("MOCK_SMS");
        assertThat(c.devCode()).matches("[0-9]{6}");
        assertThat(c.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(3)));
        assertThat(c.resendAfter()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(c.toString()).doesNotContain(c.devCode());

        for (String bad : new String[] {"01112345678", "0101234567", "010123456789", "+821012345678", null, ""}) {
            assertThatThrownBy(() -> challenge(bad)).satisfies(t -> assertThat(code(t)).isEqualTo("VALIDATION_FAILED"));
        }
    }

    @Test
    void verifyIssuesSingleTokenAndCannotBeRepeated() {
        PhoneChallengeResponse c = challenge(PHONE);
        PhoneVerificationResponse v = verify(c);
        assertThat(v.verificationToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(v.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(v.toString()).doesNotContain(v.verificationToken());

        assertThatThrownBy(() -> verify(c))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_ALREADY_VERIFIED"));
    }

    @Test
    void codeMustBeExactlySixDigitsWithoutConsumingAttempts() {
        PhoneChallengeResponse c = challenge(PHONE);
        for (String bad : new String[] {"12345", "1234567", "12345a", " 123456", null}) {
            assertThatThrownBy(() -> service.verify(c.challengeId(), new VerifyPhoneChallenge(bad)))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("VALIDATION_FAILED"));
        }
        assertThat(verify(c).verificationToken()).isNotBlank();
    }

    @Test
    void fiveWrongAttemptsLockTheChallenge() {
        PhoneChallengeResponse c = challenge(PHONE);
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.verify(c.challengeId(), new VerifyPhoneChallenge(wrong(c.devCode()))))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_CODE_MISMATCH"));
        }
        assertThatThrownBy(() -> service.verify(c.challengeId(), new VerifyPhoneChallenge(wrong(c.devCode()))))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_LOCKED");
                    assertThat(((ApiException) t).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
        // Even the correct code is refused once locked.
        assertThatThrownBy(() -> verify(c)).satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_LOCKED"));
    }

    @Test
    void challengeExpiresAfterThreeMinutes() {
        PhoneChallengeResponse c = challenge(PHONE);
        clock.advance(Duration.ofMinutes(3).minusSeconds(1));
        PhoneChallengeResponse c2 = challenge("01000000001");
        assertThat(verify(c).verificationToken()).isNotBlank();

        clock.advance(Duration.ofMinutes(3));
        assertThatThrownBy(() -> verify(c2)).satisfies(t -> {
            assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_EXPIRED");
            assertThat(((ApiException) t).status()).isEqualTo(HttpStatus.GONE);
        });
    }

    @Test
    void resendRespectsCooldownAndHourlyLimits() {
        challenge(PHONE);
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> challenge(PHONE)).satisfies(t -> {
            assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_COOLDOWN");
            assertThat(((ApiException) t).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(((ApiException) t).retryable()).isTrue();
        });
        clock.advance(Duration.ofSeconds(1));
        challenge(PHONE);
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(60));
            challenge(PHONE);
        }
        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(() -> challenge(PHONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_RATE_LIMITED"));
        // The hourly window slides.
        clock.advance(Duration.ofMinutes(57));
        assertThat(challenge(PHONE).devCode()).isNotBlank();
    }

    @Test
    void perSourceHourlyLimitApplies() {
        for (int i = 0; i < 30; i++) {
            service.createChallenge(new CreatePhoneChallenge(String.format("0109%07d", i)), "203.0.113.9");
        }
        assertThatThrownBy(() -> service.createChallenge(new CreatePhoneChallenge("01099999999"), "203.0.113.9"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_RATE_LIMITED"));
        assertThat(service.createChallenge(new CreatePhoneChallenge("01099999999"), "203.0.113.10")).isNotNull();
    }

    @Test
    void trackedStateIsBoundedAndPruned() {
        EnrollmentPolicy tiny = new EnrollmentPolicy(Duration.ofMinutes(3), Duration.ofSeconds(60), 5, 5, 1000,
                Duration.ofMinutes(10), 5, 10);
        EnrollmentService small = new EnrollmentService(new MockSmsSender(), hasher, clock, tiny);
        // Each send tracks a challenge, a phone window and (once) the source window.
        small.createChallenge(new CreatePhoneChallenge("01000000001"), "s");
        small.createChallenge(new CreatePhoneChallenge("01000000002"), "s");
        assertThatThrownBy(() -> small.createChallenge(new CreatePhoneChallenge("01000000003"), "s"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("ENROLLMENT_CAPACITY_EXCEEDED"));
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(small.createChallenge(new CreatePhoneChallenge("01000000003"), "s")).isNotNull();
    }

    @Test
    void resendInvalidatesEarlierChallengeAndUnusedToken() {
        PhoneChallengeResponse first = challenge(PHONE);
        String firstToken = verify(first).verificationToken();
        clock.advance(Duration.ofSeconds(60));
        PhoneChallengeResponse second = challenge(PHONE);
        clock.advance(Duration.ofSeconds(60));
        PhoneChallengeResponse third = challenge(PHONE);

        assertThatThrownBy(() -> service.register(personal("alice01", PHONE, firstToken)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_VERIFICATION_INVALID"));
        assertThatThrownBy(() -> verify(second))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_CHALLENGE_SUPERSEDED"));
        String token = verify(third).verificationToken();
        assertThat(service.register(personal("alice01", PHONE, token)).status()).isEqualTo("REGISTERED");
    }

    @Test
    void registrationConsumesTokenOnceAndStoresOnlyAHash() {
        String token = token(PHONE);
        RegistrationResponse r = service.register(personal("Alice.Kim", "01012345678", token));
        assertThat(r.username()).isEqualTo("Alice.Kim");
        assertThat(r.accountType()).isEqualTo("PERSONAL");
        assertThat(r.status()).isEqualTo("REGISTERED");
        assertThat(r.phoneVerification()).isEqualTo("MOCK_VERIFIED");
        assertThat(r.registeredAt()).isEqualTo(clock.instant());

        Account account = service.findByUsername("alice.kim").orElseThrow();
        assertThat(account.phone()).isEqualTo(NORMALIZED);
        assertThat(account.passwordHash().iterations()).isEqualTo(600_000);
        assertThat(account.passwordHash().algorithm()).isEqualTo("PBKDF2WithHmacSHA256");
        assertThat(hasher.matches(PASSWORD, account.passwordHash())).isTrue();
        assertThat(hasher.matches(PASSWORD + "x", account.passwordHash())).isFalse();
        assertThat(account.consent().version()).isEqualTo("2026-10-13-draft");
        assertThat(account.consent().acceptedAt()).isEqualTo(clock.instant());
        assertThat(account.phoneVerification().label()).isEqualTo("MOCK_VERIFIED");
        assertThat(account.toString()).doesNotContain(NORMALIZED).doesNotContain("홍길동");

        // Replay of the same token, even for another username, is refused.
        assertThatThrownBy(() -> service.register(personal("bob0001", PHONE, token)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_VERIFICATION_INVALID"));
    }

    @Test
    void tokenIsBoundToVerifiedPhoneAndExpires() {
        String token = token(PHONE);
        assertThatThrownBy(() -> service.register(personal("carol01", "01087654321", token)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_VERIFICATION_MISMATCH"));
        // The mismatch did not consume the proof.
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(service.register(personal("carol01", PHONE, token)).username()).isEqualTo("carol01");

        String late = token("01011112222");
        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.register(personal("carol02", "01011112222", late)))
                .satisfies(t -> assertThat(code(t)).isIn("PHONE_VERIFICATION_EXPIRED", "PHONE_VERIFICATION_INVALID"));
    }

    @Test
    void registrationRequiresVerifiedPhone() {
        assertThatThrownBy(() -> service.register(personal("dave001", PHONE, null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_VERIFICATION_REQUIRED"));
        assertThatThrownBy(() -> service.register(personal("dave001", PHONE, "A".repeat(43))))
                .satisfies(t -> assertThat(code(t)).isEqualTo("PHONE_VERIFICATION_INVALID"));
    }

    @Test
    void validationFailuresDoNotConsumeProofAndNeverEchoInput() {
        String token = token(PHONE);
        String secret = "short-pw";
        List<RegistrationRequest> invalid = List.of(
                new RegistrationRequest("PERSONAL", "erin001", secret, "홍길동", PHONE, null, null, null, null, token,
                        new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", "x".repeat(129), "홍길동", PHONE, null, null, null, null,
                        token, new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "1erin", PASSWORD, "홍길동", PHONE, null, null, null, null, token,
                        new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍", PHONE, null, null, null, null, token,
                        new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍길동", PHONE, "not-an-email", null, null,
                        null, token, new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍길동", PHONE, null, "병원", null, null,
                        token, new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("ADMIN", "erin001", PASSWORD, "홍길동", PHONE, null, null, null, null, token,
                        new Consents(true, true, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍길동", PHONE, null, null, null, null, token,
                        new Consents(true, false, true, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍길동", PHONE, null, null, null, null, token,
                        new Consents(true, true, null, EnrollmentService.CONSENT_VERSION)),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍길동", PHONE, null, null, null, null, token,
                        new Consents(true, true, true, "2025-01-01")),
                new RegistrationRequest("PERSONAL", "erin001", PASSWORD, "홍길동", PHONE, null, null, null, null, token,
                        null),
                personal("Admin-Demo", PHONE, token),
                personal("doctor-demo", PHONE, token));
        for (RegistrationRequest request : invalid) {
            assertThatThrownBy(() -> service.register(request)).satisfies(t -> {
                assertThat(t.getMessage()).doesNotContain(secret).doesNotContain(token).doesNotContain("not-an-email")
                        .doesNotContain(NORMALIZED);
                assertThat(((ApiException) t).status().value()).isIn(409, 422);
            });
        }
        assertThat(service.register(personal("erin001", PHONE, token)).status()).isEqualTo("REGISTERED");
    }

    @Test
    void passwordIsNotTrimmedAndAllowsAnyCharacters() {
        String token = token(PHONE);
        RegistrationRequest r = new RegistrationRequest("PERSONAL", "frank01", "  비밀 번호 😀 ok  ", "홍길동", PHONE,
                "frank@example.com", "", " ", null, token, new Consents(true, true, true, "2026-10-13-draft"));
        service.register(r);
        Account account = service.findByUsername("FRANK01").orElseThrow();
        assertThat(hasher.matches("  비밀 번호 😀 ok  ", account.passwordHash())).isTrue();
        assertThat(hasher.matches("비밀 번호 😀 ok", account.passwordHash())).isFalse();
        assertThat(account.email()).isEqualTo("frank@example.com");
        assertThat(r.toString()).doesNotContain("비밀").doesNotContain(token).doesNotContain(NORMALIZED);
    }

    @Test
    void clinicianSelfRegistrationIsRejectedWithoutConsumingProof() {
        String token = token(PHONE);
        RegistrationRequest clinician = new RegistrationRequest("CLINICIAN", "drlee01", PASSWORD, "이영상", PHONE,
                "lee@example.org", "서울영상의학과의원", "12345", "영상의학과", token,
                new Consents(true, true, true, EnrollmentService.CONSENT_VERSION));
        assertThatThrownBy(() -> service.register(clinician))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("CLINICIAN_SELF_REGISTRATION_DISABLED");
                    assertThat(((ApiException) t).status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                });
        assertThat(service.findByUsername("drlee01")).isEmpty();
        // The phone proof is still usable for a personal account.
        assertThat(service.register(personal("drlee01", PHONE, token)).status()).isEqualTo("REGISTERED");
    }

    @Test
    void usernamesAreCaseInsensitiveUnique() {
        service.register(personal("grace01", PHONE, token(PHONE)));
        String other = token("01022223333");
        assertThatThrownBy(() -> service.register(personal("GRACE01", "01022223333", other)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("USERNAME_UNAVAILABLE"));
        // The losing request kept its proof.
        assertThat(service.register(personal("grace02", "01022223333", other)).username()).isEqualTo("grace02");
    }

    @Test
    void concurrentDuplicateSignupCreatesExactlyOneAccount() throws Exception {
        int contenders = 4;
        List<String> phones = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String phone = String.format("0105555%04d", i);
            phones.add(phone);
            tokens.add(token(phone));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                RegistrationRequest request = personal("racer01", phones.get(i), tokens.get(i));
                Callable<String> task = () -> {
                    start.await();
                    try {
                        service.register(request);
                        return "OK";
                    } catch (ApiException e) {
                        return e.code();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).containsOnlyOnce("OK");
            assertThat(outcomes.stream().filter("USERNAME_UNAVAILABLE"::equals)).hasSize(contenders - 1);
        } finally {
            pool.shutdownNow();
        }
        // Losers' proofs are still valid.
        int reused = 0;
        for (int i = 0; i < contenders; i++) {
            try {
                service.register(personal("racer-alt" + i, phones.get(i), tokens.get(i)));
                reused++;
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("PHONE_VERIFICATION_INVALID");
            }
        }
        assertThat(reused).isEqualTo(contenders - 1);
    }

    @Test
    void failedSendInvalidatesChallenge() {
        SmsSender failing = new MockSmsSender() {
            @Override
            public void send(String normalizedPhone, String message) {
                throw new IllegalStateException("provider down");
            }
        };
        EnrollmentService broken = new EnrollmentService(failing, hasher, clock, EnrollmentPolicy.defaults());
        assertThatThrownBy(() -> broken.createChallenge(new CreatePhoneChallenge(PHONE), "s"))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("SMS_SEND_FAILED");
                    assertThat(((ApiException) t).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
}
