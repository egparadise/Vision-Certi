package com.inviz.visioncerti.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.doctor.DoctorPolicy;
import com.inviz.visioncerti.enrollment.EnrollmentPolicy;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.admin.SystemSettings.UpdateSettingRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemSettingsTest {

    private static final RequestActor ADMIN = new RequestActor("ops-tenant", "admin-1", ActorRole.ADMIN);

    private static SystemSettings settings() {
        Map<SettingKey, Long> defaults = SystemSettings.defaults(DoctorPolicy.defaults(), EnrollmentPolicy.defaults(),
                20_971_520L, Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofMinutes(10));
        return new SystemSettings(new AuditService(Clock.systemUTC()), Clock.systemUTC(), defaults, "ops-tenant");
    }

    @Test
    void defaultsComeFromTheDeploymentPolicies() {
        SystemSettings s = settings();
        assertThat(s.doctorPolicy(DoctorPolicy.defaults())).isEqualTo(DoctorPolicy.defaults());
        assertThat(s.enrollmentPolicy(EnrollmentPolicy.defaults())).isEqualTo(EnrollmentPolicy.defaults());
        assertThat(s.duration(SettingKey.PACS_HANDOFF_TTL)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void overridesAreAppliedToDoctorAndEnrollmentPoliciesOnly() {
        SystemSettings s = settings();
        s.update(ADMIN, "doctor.session-ttl", new UpdateSettingRequest(1_800L, "shorter sessions", 0L));
        s.update(ADMIN, "doctor.max-identity-failures-per-invitation", new UpdateSettingRequest(3L, "stricter", 0L));
        s.update(ADMIN, "enrollment.phone-hourly-limit", new UpdateSettingRequest(2L, "fewer texts", 0L));
        DoctorPolicy doctor = s.doctorPolicy(DoctorPolicy.defaults());
        assertThat(doctor.sessionTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(doctor.maxIdentityFailuresPerInvitation()).isEqualTo(3);
        assertThat(doctor.challengeTtl()).isEqualTo(DoctorPolicy.defaults().challengeTtl());  // not a setting
        assertThat(doctor.maxDoctors()).isEqualTo(DoctorPolicy.defaults().maxDoctors());
        EnrollmentPolicy enrollment = s.enrollmentPolicy(EnrollmentPolicy.defaults());
        assertThat(enrollment.phoneHourlyLimit()).isEqualTo(2);
        assertThat(enrollment.maxVerifyAttempts()).isEqualTo(EnrollmentPolicy.defaults().maxVerifyAttempts());
    }

    @Test
    void everyKeyNeedsADefault() {
        Map<SettingKey, Long> partial = new EnumMap<>(SettingKey.class);
        partial.put(SettingKey.DOCUMENT_MAX_BYTES, 1L);
        assertThatThrownBy(() -> new SystemSettings(new AuditService(Clock.systemUTC()), Clock.systemUTC(), partial, ""))
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    void withoutEditorTenantsEverythingIsReadOnly() {
        Map<SettingKey, Long> defaults = SystemSettings.defaults(DoctorPolicy.defaults(), EnrollmentPolicy.defaults(),
                1L, Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofMinutes(10));
        SystemSettings s = new SystemSettings(new AuditService(Clock.systemUTC()), Clock.systemUTC(), defaults, " ");
        assertThat(s.list(ADMIN).editable()).isFalse();
        assertThatThrownBy(() -> s.update(ADMIN, "doctor.session-ttl", new UpdateSettingRequest(1_800L, "x y", 0L)))
                .hasMessageContaining("VISIONCERTI_ADMIN_SETTINGS_TENANT_IDS");
    }
}
