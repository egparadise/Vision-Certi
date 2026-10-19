package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.enrollment.PasswordHasher;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import com.inviz.visioncerti.store.Tx;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Password sessions are enrollment-only; never accepted by DoctorService or signing APIs. */
@Service
public class PhysicianAccountService {
    public static final String CONSENT_VERSION = "physician-account-2026-10-17";
    public record Register(String username, String password, String name, String phone, String licenseNumber,
            String centerName, Boolean privacyConsent, String consentVersion) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record Login(String username, String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    record Account(UUID id, String tenantId, UUID doctorId, String username, String usernameKey,
            String phone, PasswordHasher.PasswordHash passwordHash, String consentVersion, Instant createdAt) {
        @Override public String toString() { return "Account[" + id + "]"; }
    }
    record Session(UUID id, String tenantId, UUID accountId, String tokenHash, Instant expiresAt) {
        @Override public String toString() { return "Session[" + id + "]"; }
    }
    public record Config(boolean available, String consentVersion, String scope) {}
    public record View(UUID accountId, String username, DoctorDtos.DoctorResponse doctor,
            boolean phoneVerified, String scope) {}
    public record Receipt(String enrollmentToken, Instant expiresAt, View account) {
        @Override public String toString() { return "Receipt[token=redacted]"; }
    }
    static final Kind<Account> ACCOUNTS = Kind.of("physician_accounts", Account.class)
            .id(Account::id).tenant(Account::tenantId)
            .unique("username_key", Account::usernameKey,
                    () -> ApiException.conflict("USERNAME_UNAVAILABLE", "사용할 수 없는 아이디입니다."))
            .unique("doctor_id", a -> a.doctorId().toString(), null).build();
    static final Kind<Session> SESSIONS = Kind.of("physician_account_sessions", Session.class)
            .id(Session::id).tenant(Session::tenantId).expiresAt(Session::expiresAt)
            .unique("token_hash", Session::tokenHash, null)
            .column("account_id", s -> s.accountId().toString()).build();
    private final Store store;
    private final AuditService audit;
    private final DoctorService doctors;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final String tenant;
    private final SecureRandom random = new SecureRandom();
    private final PasswordHasher.PasswordHash dummyHash;

    public PhysicianAccountService(AuditService audit, DoctorService doctors, PasswordHasher hasher, Clock clock,
            @Value("${visioncerti.physician-account.tenant-id:}") String tenant) {
        this.store = audit.store(); this.audit = audit; this.doctors = doctors; this.hasher = hasher;
        this.clock = clock; this.tenant = tenant.strip();
        this.dummyHash = hasher.hash(UUID.randomUUID().toString());
    }
    public Config config() { return new Config(!tenant.isEmpty(), CONSENT_VERSION, "ENROLLMENT_ONLY"); }
    private void enabled() {
        if (tenant.isEmpty()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PHYSICIAN_SIGNUP_NOT_CONFIGURED", "의사 가입을 담당할 기관이 설정되지 않았습니다.");
    }
    public Receipt register(Register body, String source) {
        enabled(); doctors.countLoginChallenge("signup:" + source);
        if (body == null) throw invalid();
        String username = username(body.username());
        password(body.password());
        String name = text(body.name(), 2, 50);
        String center = text(body.centerName(), 2, 100);
        String license = text(body.licenseNumber(), 1, 20);
        if (!license.matches("[0-9]{1,20}")) throw invalid();
        String phone = body.phone() == null ? "" : body.phone().replace("-", "").replace(" ", "");
        if (!phone.matches("010[0-9]{8}")) throw invalid();
        if (!Boolean.TRUE.equals(body.privacyConsent()) || !CONSENT_VERSION.equals(body.consentVersion())) {
            throw ApiException.unprocessable("ACCOUNT_CONSENT_REQUIRED", "최신 가입 정보 수집·이용 동의가 필요합니다.");
        }
        String key = Hashing.sha256Hex(username.toLowerCase(Locale.ROOT));
        PasswordHasher.PasswordHash hash = hasher.hash(body.password());
        return store.tx(tx -> {
            tx.lock("physician-username|" + key);
            if (tx.findOne(ACCOUNTS, "username_key", key).isPresent())
                throw ApiException.conflict("USERNAME_UNAVAILABLE", "사용할 수 없는 아이디입니다.");
            DoctorService.DoctorRef ref = doctors.selfEnrollAccount(tenant, name, license, center);
            Account account = new Account(UUID.randomUUID(), tenant, ref.id(), username, key, phone, hash,
                    CONSENT_VERSION, clock.instant());
            tx.insert(ACCOUNTS, account);
            audit.record(tenant, ref.id().toString(), "PHYSICIAN_ACCOUNT_REGISTERED", "DOCTOR", ref.id().toString(),
                    AuditService.SUCCESS, Map.of("consentVersion", CONSENT_VERSION, "scope", "ENROLLMENT_ONLY"));
            return issue(tx, account);
        });
    }
    public Receipt login(Login body, String source) {
        enabled(); doctors.countLoginChallenge("account-login:" + source);
        if (body == null || body.username() == null || body.username().length() > 32
                || body.password() == null || body.password().length() > 256) throw unauthorized();
        String key = Hashing.sha256Hex(body.username().strip().toLowerCase(Locale.ROOT));
        doctors.countLoginChallenge("account-user:" + key);
        Account account = store.tx(tx -> tx.findOne(ACCOUNTS, "username_key", key).orElse(null));
        boolean valid = hasher.matches(body.password(), account == null ? dummyHash : account.passwordHash());
        if (!valid || account == null) throw unauthorized();
        return store.tx(tx -> {
            requireAllowed(account);
            audit.record(account.tenantId(), account.doctorId().toString(), "PHYSICIAN_ACCOUNT_RESUMED", "DOCTOR",
                    account.doctorId().toString(), AuditService.SUCCESS, Map.of("scope", "ENROLLMENT_ONLY"));
            return issue(tx, account);
        });
    }
    private Receipt issue(Tx tx, Account account) {
        Instant now = clock.instant();
        tx.deleteExpired(SESSIONS, now);
        // One enrollment session per account. A new password login invalidates the previous one.
        tx.lock("physician-session|" + account.id());
        tx.deleteWhere(SESSIONS, "account_id", account.id().toString());
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = "vca1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = now.plus(Duration.ofMinutes(20));
        tx.insert(SESSIONS, new Session(UUID.randomUUID(), account.tenantId(), account.id(), Hashing.sha256Hex(token), expires));
        return new Receipt(token, expires, view(account));
    }
    public View me(String authorization) { return view(authenticate(authorization)); }
    public DoctorService.DoctorRef doctor(String authorization) {
        return doctors.doctorRef(authenticate(authorization).doctorId());
    }
    public void logout(String authorization) {
        store.run(tx -> { Session session = session(tx, authorization); tx.delete(SESSIONS, session.id()); });
    }
    private Account authenticate(String authorization) {
        enabled();
        return store.tx(tx -> {
            Session session = session(tx, authorization);
            Account account = tx.get(ACCOUNTS, session.accountId()).orElseThrow(PhysicianAccountService::unauthorized);
            if (!account.tenantId().equals(session.tenantId())) throw unauthorized();
            requireAllowed(account); return account;
        });
    }
    private Session session(Tx tx, String authorization) {
        if (authorization == null || !authorization.matches("Bearer vca1\\.[A-Za-z0-9_-]{43}")) throw unauthorized();
        return tx.findOne(SESSIONS, "token_hash", Hashing.sha256Hex(authorization.substring(7)))
                .filter(s -> clock.instant().isBefore(s.expiresAt())).orElseThrow(PhysicianAccountService::unauthorized);
    }
    private void requireAllowed(Account account) {
        DoctorService.DoctorRef ref = doctors.doctorRef(account.doctorId());
        if (ref == null || !ref.tenantId().equals(account.tenantId()) || ref.status().blocked())
            throw ApiException.forbidden("DOCTOR_NOT_ALLOWED", "정지 또는 폐지된 계정입니다.");
    }
    private View view(Account a) { return new View(a.id(), a.username(), doctors.doctorResponse(a.doctorId()), false, "ENROLLMENT_ONLY"); }
    private static String username(String value) {
        String result = value == null ? "" : value.strip();
        if (!result.matches("[A-Za-z][A-Za-z0-9_.-]{3,31}")
                || result.equalsIgnoreCase("admin-demo") || result.equalsIgnoreCase("doctor-demo")) throw invalid();
        return result;
    }
    private static void password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 12 || value.length() > 128) throw invalid();
    }
    private static String text(String value, int min, int max) {
        String result = value == null ? "" : value.strip();
        if (result.length() < min || result.length() > max || result.chars().anyMatch(Character::isISOControl)) throw invalid();
        return result;
    }
    private static ApiException invalid() { return ApiException.unprocessable("ACCOUNT_INPUT_INVALID", "가입 정보를 확인해 주세요. ID는 영문으로 시작하는 4~32자, 비밀번호는 12~128자입니다."); }
    private static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_AUTH_REQUIRED", "계정 정보가 올바르지 않거나 가입 세션이 만료되었습니다."); }
}
