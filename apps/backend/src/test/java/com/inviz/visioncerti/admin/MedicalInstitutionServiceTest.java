package com.inviz.visioncerti.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorPolicy;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.DoctorDtos.PreregisterDoctor;
import com.inviz.visioncerti.doctor.credential.DevSimulatedCredentialProvider;
import com.inviz.visioncerti.doctor.identity.TestGoogleTokens;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MedicalInstitutionServiceTest {
    private static final RequestActor ADMIN_A = new RequestActor("tenant-a", "admin-a", ActorRole.ADMIN);
    private static final RequestActor ADMIN_B = new RequestActor("tenant-b", "admin-b", ActorRole.ADMIN);
    private static final RequestActor DOCTOR = new RequestActor("tenant-a", "doctor", ActorRole.DOCTOR);

    @Test
    void institutionRegistrationAndAssignmentAreDurableTenantScopedAndAudited() {
        var clock = new MutableClock(Instant.parse("2026-10-19T00:00:00Z"));
        var audit = new AuditService(clock);
        var doctors = new DoctorService(audit, new DevSimulatedCredentialProvider(clock),
                new TestGoogleTokens(clock).verifier(), clock, DoctorPolicy.defaults(), true, false, true);
        var directory = new MedicalInstitutionService(audit, doctors, clock);
        var physician = doctors.preregister(ADMIN_A, new PreregisterDoctor("김의사", "123456",
                "실제 기관 입력 전", "doctor@example.com", "evidence://id", "evidence://license"))
                .doctor();

        assertThatThrownBy(() -> directory.create(DOCTOR,
                new MedicalInstitutionService.CreateRequest("H001", "서울 영상의학과")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ADMIN_REQUIRED");
        var institution = directory.create(ADMIN_A,
                new MedicalInstitutionService.CreateRequest("H001", "서울 영상의학과"));
        assertThat(directory.list(ADMIN_B)).isEmpty();
        assertThatThrownBy(() -> directory.create(ADMIN_A,
                new MedicalInstitutionService.CreateRequest("H001", "다른 이름")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INSTITUTION_CODE_EXISTS");
        assertThatThrownBy(() -> directory.assign(ADMIN_B, institution.id(), physician.id()))
                .isInstanceOf(ApiException.class);

        assertThat(directory.assign(ADMIN_A, institution.id(), physician.id()).doctorIds())
                .containsExactly(physician.id());
        assertThat(directory.assign(ADMIN_A, institution.id(), physician.id()).doctorIds())
                .containsExactly(physician.id());
        assertThat(directory.institutionsFor(ADMIN_A, physician.id()))
                .containsExactly("서울 영상의학과");
        assertThat(directory.unassign(ADMIN_A, institution.id(), physician.id()).doctorIds()).isEmpty();
        assertThat(audit.list("tenant-a").stream().map(e -> e.action()))
                .contains("MEDICAL_INSTITUTION_REGISTERED", "MEDICAL_INSTITUTION_DOCTOR_ASSIGNED",
                        "MEDICAL_INSTITUTION_DOCTOR_UNASSIGNED");
    }
}
