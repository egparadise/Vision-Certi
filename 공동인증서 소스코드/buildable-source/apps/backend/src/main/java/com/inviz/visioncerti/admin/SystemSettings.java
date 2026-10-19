package com.inviz.visioncerti.admin;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorPolicy;
import com.inviz.visioncerti.enrollment.EnrollmentPolicy;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Durable, system-wide administrator overrides of operational policy values ({@link SettingKey}). The deployment
 * configuration supplies each default; an override must lie in the key's hard range and is audited with its reason.
 * Every administrator may read the settings; only administrators of the tenants listed in
 * {@code visioncerti.admin.settings-tenant-ids} may change them, because they apply to every tenant.
 *
 * <p>Services read values through {@link #value}; overrides are cached for at most {@link #REFRESH} so a change made
 * on another instance takes effect within that time (immediately on this one).
 */
@Service
public class SystemSettings {

    static final Duration REFRESH = Duration.ofSeconds(5);

    /** One override. Absent row = the deployment default (revision 0). */
    public record SystemSetting(UUID id, String key, long value, long revision, String reason, Instant updatedAt,
            String updatedBy, String updatedByTenant) {
    }

    public record SettingView(String key, String group, String unit, String label, String description, long min,
            long max, long defaultValue, long value, boolean overridden, long revision, String reason,
            Instant updatedAt, String updatedBy, String updatedByTenant) {
    }

    public record SettingsResponse(boolean editable, String editableReason, List<SettingView> items) {
    }

    public record UpdateSettingRequest(Long value, String reason, Long expectedRevision) {
    }

    public record ResetSettingRequest(String reason, Long expectedRevision) {
    }

    static final Kind<SystemSetting> SETTINGS = Kind.of("system_settings", SystemSetting.class)
            .id(SystemSetting::id)
            .unique("setting_key", SystemSetting::key,
                    () -> ApiException.conflict("SETTING_CHANGED_CONCURRENTLY", "The setting was changed meanwhile"))
            .build();

    private final Store store;
    private final AuditService audit;
    private final Clock clock;
    private final Map<SettingKey, Long> defaults;
    private final Set<String> editorTenants;

    private volatile Map<SettingKey, SystemSetting> cache = Map.of();
    private volatile long loadedAtNanos;
    private volatile boolean loaded;

    @Autowired
    public SystemSettings(AuditService audit, Clock clock, DoctorPolicy doctorPolicy, EnrollmentPolicy enrollmentPolicy,
            @Value("${visioncerti.documents.max-bytes:20971520}") long documentMaxBytes,
            @Value("${visioncerti.sign-requests.ttl:PT10M}") Duration signRequestTtl,
            @Value("${visioncerti.pacs.handoff-ttl:PT60S}") Duration handoffTtl,
            @Value("${visioncerti.pacs.max-certificate-login-age:PT10M}") Duration maxCertificateLoginAge,
            @Value("${visioncerti.admin.settings-tenant-ids:}") String editorTenants) {
        this(audit, clock, defaults(doctorPolicy, enrollmentPolicy, documentMaxBytes, signRequestTtl, handoffTtl,
                maxCertificateLoginAge), editorTenants);
    }

    public SystemSettings(AuditService audit, Clock clock, Map<SettingKey, Long> defaults, String editorTenants) {
        this.audit = audit;
        this.store = audit.store();
        this.clock = clock;
        EnumMap<SettingKey, Long> all = new EnumMap<>(SettingKey.class);
        for (SettingKey key : SettingKey.values()) {
            Long value = defaults.get(key);
            if (value == null) {
                throw new IllegalStateException("FAIL_CLOSED: no default for setting " + key.id());
            }
            all.put(key, value);
        }
        this.defaults = Collections.unmodifiableMap(all);
        this.editorTenants = parseTenants(editorTenants);
    }

    static Map<SettingKey, Long> defaults(DoctorPolicy doctor, EnrollmentPolicy enrollment, long documentMaxBytes,
            Duration signRequestTtl, Duration handoffTtl, Duration maxCertificateLoginAge) {
        EnumMap<SettingKey, Long> d = new EnumMap<>(SettingKey.class);
        d.put(SettingKey.DOCUMENT_MAX_BYTES, documentMaxBytes);
        d.put(SettingKey.SIGN_REQUEST_TTL, signRequestTtl.toSeconds());
        d.put(SettingKey.PACS_HANDOFF_TTL, handoffTtl.toSeconds());
        d.put(SettingKey.PACS_MAX_CERTIFICATE_LOGIN_AGE, maxCertificateLoginAge.toSeconds());
        d.put(SettingKey.DOCTOR_INVITATION_TTL, doctor.invitationTtl().toSeconds());
        d.put(SettingKey.DOCTOR_SESSION_TTL, doctor.sessionTtl().toSeconds());
        d.put(SettingKey.DOCTOR_MAX_IDENTITY_FAILURES, (long) doctor.maxIdentityFailuresPerInvitation());
        d.put(SettingKey.DOCTOR_MAX_CHALLENGES_PER_INVITATION, (long) doctor.maxChallengesPerInvitation());
        d.put(SettingKey.DOCTOR_SOURCE_FAILURE_HOURLY_LIMIT, (long) doctor.sourceFailureHourlyLimit());
        d.put(SettingKey.DOCTOR_SOURCE_CHALLENGE_HOURLY_LIMIT, (long) doctor.sourceChallengeHourlyLimit());
        d.put(SettingKey.ENROLLMENT_PHONE_HOURLY_LIMIT, (long) enrollment.phoneHourlyLimit());
        d.put(SettingKey.ENROLLMENT_SOURCE_HOURLY_LIMIT, (long) enrollment.sourceHourlyLimit());
        return d;
    }

    // ------------------------------------------------------------------ effective values (for services)

    public long value(SettingKey key) {
        SystemSetting override = current().get(key);
        return override == null ? defaults.get(key) : override.value();
    }

    public Duration duration(SettingKey key) {
        return Duration.ofSeconds(value(key));
    }

    public int intValue(SettingKey key) {
        return Math.toIntExact(value(key));
    }

    /** {@link DoctorPolicy} with the administrator overrides applied (limits not exposed as settings unchanged). */
    public DoctorPolicy doctorPolicy(DoctorPolicy base) {
        return new DoctorPolicy(duration(SettingKey.DOCTOR_INVITATION_TTL), base.challengeTtl(),
                duration(SettingKey.DOCTOR_SESSION_TTL), intValue(SettingKey.DOCTOR_MAX_IDENTITY_FAILURES),
                intValue(SettingKey.DOCTOR_MAX_CHALLENGES_PER_INVITATION),
                intValue(SettingKey.DOCTOR_SOURCE_FAILURE_HOURLY_LIMIT),
                intValue(SettingKey.DOCTOR_SOURCE_CHALLENGE_HOURLY_LIMIT), base.maxTrackedEntries(), base.maxDoctors());
    }

    public EnrollmentPolicy enrollmentPolicy(EnrollmentPolicy base) {
        return new EnrollmentPolicy(base.challengeTtl(), base.resendCooldown(), base.maxVerifyAttempts(),
                intValue(SettingKey.ENROLLMENT_PHONE_HOURLY_LIMIT), intValue(SettingKey.ENROLLMENT_SOURCE_HOURLY_LIMIT),
                base.verificationTokenTtl(), base.maxTrackedEntries(), base.maxAccounts());
    }

    private Map<SettingKey, SystemSetting> current() {
        if (!loaded || System.nanoTime() - loadedAtNanos > REFRESH.toNanos()) {
            reload();
        }
        return cache;
    }

    private void reload() {
        Map<SettingKey, SystemSetting> fresh = store.tx(tx -> {
            EnumMap<SettingKey, SystemSetting> rows = new EnumMap<>(SettingKey.class);
            for (SettingKey key : SettingKey.values()) {
                tx.get(SETTINGS, idOf(key)).ifPresent(s -> rows.put(key, s));
            }
            return rows;
        });
        cache = Collections.unmodifiableMap(fresh);
        loadedAtNanos = System.nanoTime();
        loaded = true;
    }

    // ------------------------------------------------------------------ administration

    public SettingsResponse list(RequestActor actor) {
        requireAdmin(actor);
        reload();
        boolean editable = editorTenants.contains(actor.tenantId());
        String reason = editable ? null : editorTenants.isEmpty()
                ? "VISIONCERTI_ADMIN_SETTINGS_TENANT_IDS가 설정되지 않아 모든 관리자에게 읽기 전용입니다."
                : "운영 정책은 시스템 전체에 적용되므로 지정된 운영 기관의 관리자만 변경할 수 있습니다.";
        return new SettingsResponse(editable, reason, Arrays.stream(SettingKey.values()).map(this::view).toList());
    }

    public SettingView update(RequestActor actor, String keyId, UpdateSettingRequest body) {
        SettingKey key = requireEditor(actor, keyId);
        if (body == null || body.value() == null) {
            throw invalid("value is required");
        }
        long value = body.value();
        if (value < key.min() || value > key.max()) {
            throw ApiException.unprocessable("SETTING_OUT_OF_RANGE",
                    key.label() + " 값은 " + key.min() + "~" + key.max() + " 범위여야 합니다.");
        }
        String reason = reason(body.reason());
        store.run(tx -> {
            tx.lock("system-setting|" + key.id());
            SystemSetting existing = tx.lockGet(SETTINGS, idOf(key)).orElse(null);
            long revision = existing == null ? 0 : existing.revision();
            requireRevision(body.expectedRevision(), revision);
            long previous = existing == null ? defaults.get(key) : existing.value();
            SystemSetting next = new SystemSetting(idOf(key), key.id(), value, revision + 1, reason, clock.instant(),
                    actor.actorId(), actor.tenantId());
            if (existing == null) {
                tx.insert(SETTINGS, next);
            } else {
                tx.update(SETTINGS, next);
            }
            audit.record(actor.tenantId(), actor.actorId(), "SYSTEM_SETTING_CHANGED", "SYSTEM_SETTING", key.id(),
                    AuditService.SUCCESS, Map.of("from", Long.toString(previous), "to", Long.toString(value),
                            "reason", reason));
        });
        reload();
        return view(key);
    }

    public SettingView reset(RequestActor actor, String keyId, ResetSettingRequest body) {
        SettingKey key = requireEditor(actor, keyId);
        String reason = reason(body == null ? null : body.reason());
        store.run(tx -> {
            tx.lock("system-setting|" + key.id());
            SystemSetting existing = tx.lockGet(SETTINGS, idOf(key)).orElse(null);
            requireRevision(body == null ? null : body.expectedRevision(), existing == null ? 0 : existing.revision());
            if (existing == null) {
                throw ApiException.conflict("SETTING_ALREADY_DEFAULT", "이미 배포 기본값을 사용하고 있습니다.");
            }
            tx.delete(SETTINGS, existing.id());
            audit.record(actor.tenantId(), actor.actorId(), "SYSTEM_SETTING_RESET", "SYSTEM_SETTING", key.id(),
                    AuditService.SUCCESS, Map.of("from", Long.toString(existing.value()),
                            "to", Long.toString(defaults.get(key)), "reason", reason));
        });
        reload();
        return view(key);
    }

    private SettingView view(SettingKey key) {
        SystemSetting s = cache.get(key);
        long def = defaults.get(key);
        return new SettingView(key.id(), key.group().name(), key.unit().name(), key.label(), key.description(),
                key.min(), key.max(), def, s == null ? def : s.value(), s != null, s == null ? 0 : s.revision(),
                s == null ? null : s.reason(), s == null ? null : s.updatedAt(), s == null ? null : s.updatedBy(),
                s == null ? null : s.updatedByTenant());
    }

    public boolean editableBy(RequestActor actor) {
        return actor.role() == ActorRole.ADMIN && editorTenants.contains(actor.tenantId());
    }

    public Set<String> editorTenants() {
        return editorTenants;
    }

    private SettingKey requireEditor(RequestActor actor, String keyId) {
        requireAdmin(actor);
        SettingKey key = SettingKey.byId(keyId)
                .orElseThrow(() -> ApiException.notFound("SETTING_NOT_FOUND", "Unknown setting"));
        if (!editorTenants.contains(actor.tenantId())) {
            throw ApiException.forbidden("SETTINGS_READ_ONLY",
                    "운영 정책은 지정된 운영 기관(VISIONCERTI_ADMIN_SETTINGS_TENANT_IDS)의 관리자만 변경할 수 있습니다.");
        }
        return key;
    }

    private static void requireRevision(Long expected, long actual) {
        if (expected == null) {
            throw invalid("expectedRevision is required");
        }
        if (expected != actual) {
            throw ApiException.conflict("SETTING_CHANGED_CONCURRENTLY",
                    "다른 관리자가 먼저 이 설정을 변경했습니다. 새로고침 후 다시 시도하세요.");
        }
    }

    private static String reason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.length() < 2 || reason.length() > 200 || reason.chars().anyMatch(Character::isISOControl)) {
            throw invalid("변경 사유는 2~200자로 입력해야 합니다.");
        }
        return reason;
    }

    static UUID idOf(SettingKey key) {
        return UUID.nameUUIDFromBytes(("vision-certi:system-setting:" + key.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> parseTenants(String raw) {
        Set<String> tenants = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    tenants.add(part.strip());
                }
            }
        }
        return Collections.unmodifiableSet(tenants);
    }

    private static void requireAdmin(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_ROLE_REQUIRED", "Administrator role required");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("VALIDATION_FAILED", message);
    }
}
