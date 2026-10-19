package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.certificate.CertificateEnrollmentService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.CertificateGate.EnrollmentBinding;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.DoctorService.DoctorRef;
import com.inviz.visioncerti.doctor.DoctorStatus;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.EnrollmentStartRequest;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.EnrollmentStartResponse;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.EnrollmentStatusRequest;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.EnrollmentStatusResponse;
import org.springframework.stereotype.Service;

/** PACS account identities are accepted only from the configured HMAC service client. */
@Service
public final class PacsEnrollmentService {
    private final DoctorService doctors;
    private final CertificateEnrollmentService certificates;

    public PacsEnrollmentService(DoctorService doctors, CertificateEnrollmentService certificates) {
        this.doctors = doctors;
        this.certificates = certificates;
    }

    public EnrollmentStartResponse start(IntegrationClient client, EnrollmentStartRequest body) {
        if (body == null) throw invalid();
        String key = accountKey(client, body.tenantId(), body.pacsUserId());
        DoctorRef ref = doctors.selfEnrollFromPacs(key, body.tenantId(), client.actorId(),
                body.name(), body.licenseNumber(), body.centerName());
        return new EnrollmentStartResponse(ref.id(), certificates.createPacsEnrollmentChallenge(ref,
                client.actorId() + ":" + key));
    }

    public EnrollmentStatusResponse status(IntegrationClient client, EnrollmentStatusRequest body) {
        if (body == null) throw invalid();
        String key = accountKey(client, body.tenantId(), body.pacsUserId());
        DoctorRef ref = doctors.pacsAccountDoctor(key);
        if (ref == null) return new EnrollmentStatusResponse(null, "NOT_REGISTERED", null, null, null);
        String license = doctors.registeredLicenseNumber(ref.id()).orElseThrow();
        if (ref.status().blocked()) {
            return new EnrollmentStatusResponse(ref.id(), ref.status().name(), null, null, license);
        }
        if (ref.status() != DoctorStatus.ACTIVE && !certificates.hasConsentedPendingEnrollment(ref.id())) {
            // A created PACS doctor or an interrupted proof is not a completed registration.
            return new EnrollmentStatusResponse(ref.id(), "NOT_REGISTERED", null, null, license);
        }
        if (ref.status() != DoctorStatus.ACTIVE || !certificates.hasActiveEnrollment(ref.id())) {
            return new EnrollmentStatusResponse(ref.id(), "PENDING", null, null, license);
        }
        // Recheck trust, expiry and fresh revocation data. Never use a cached ACTIVE flag as proof.
        EnrollmentBinding binding = certificates.assertSigningAllowed(ref.id());
        DoctorRef current = doctors.pacsAccountDoctor(key);
        if (current == null || current.status() != DoctorStatus.ACTIVE) {
            throw ApiException.forbidden("DOCTOR_NOT_ALLOWED", "의사 계정 상태가 변경되었습니다.");
        }
        certificates.confirmBinding(ref.id(), binding);
        return new EnrollmentStatusResponse(ref.id(), "ACTIVE", binding.registrationId(),
                binding.fingerprintSha256(), license);
    }

    private static String accountKey(IntegrationClient client, String tenantId, String pacsUserId) {
        if (!client.allowsTenant(tenantId)) {
            throw ApiException.forbidden("INTEGRATION_TENANT_FORBIDDEN", "허용되지 않은 기관입니다.");
        }
        if (pacsUserId == null || !pacsUserId.matches("[A-Za-z0-9_.:@-]{1,128}")) throw invalid();
        return Hashing.sha256Hex(client.clientId() + "\u0000" + tenantId + "\u0000" + pacsUserId);
    }

    private static ApiException invalid() {
        return ApiException.unprocessable("VALIDATION_FAILED", "PACS 계정 정보가 올바르지 않습니다.");
    }
}
