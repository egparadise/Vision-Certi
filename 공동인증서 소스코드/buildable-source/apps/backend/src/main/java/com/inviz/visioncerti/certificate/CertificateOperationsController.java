package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.admin.MedicalInstitutionService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.Signature;
import com.inviz.visioncerti.signing.SignatureService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped operational view. Counts are derived from durable audit events, not browser state. */
@RestController
@RequestMapping("/v1/admin/certificate-operations")
public class CertificateOperationsController {
    public record CertificateRow(String doctorId, String name, String pacsLoginId,
            String licenseNumber, String specialistQualificationNumber, String issuer,
            Instant notAfter, String fingerprintSha256, String registrationStatus,
            long loginAttempts, long loginApproved, long reportApprovalRequests, long signedReports,
            Instant lastLoginAt, Instant lastReportRequestAt, String pacsSessionId,
            Instant connectionExpiresAt, long extensionCount, List<String> institutionNames) { }

    private final DoctorService doctors;
    private final CertificateEnrollmentService certificates;
    private final AuditService audit;
    private final SignatureService signatures;
    private final MedicalInstitutionService institutions;

    public CertificateOperationsController(DoctorService doctors, CertificateEnrollmentService certificates,
            AuditService audit, SignatureService signatures, MedicalInstitutionService institutions) {
        this.doctors = doctors;
        this.certificates = certificates;
        this.audit = audit;
        this.signatures = signatures;
        this.institutions = institutions;
    }

    @GetMapping
    public ResponseEntity<List<CertificateRow>> list(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_REQUIRED", "Administrator access is required");
        }
        List<AuditEvent> events = audit.list(actor.tenantId());
        List<Signature> completed = signatures.list(actor);
        List<CertificateRow> rows = doctors.list(actor).stream().filter(doctor -> !testDoctor(doctor)).flatMap(doctor ->
                certificates.listForAdmin(actor, doctor.id()).stream()
                        .filter(cert -> !testCertificate(cert))
                        .map(cert -> row(doctor, cert, events, completed,
                                institutions.institutionsFor(actor, doctor.id())))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    private static CertificateRow row(DoctorResponse doctor, CertificateDtos.EnrollmentResponse cert,
            List<AuditEvent> events, List<Signature> completed, List<String> institutionNames) {
        String doctorId = doctor.id().toString();
        String fingerprint = cert.fingerprintSha256();
        List<AuditEvent> logins = events.stream().filter(e -> doctorId.equals(e.targetId())
                && "PACS_CERTIFICATE_LOGIN_COMPLETED".equals(e.action())
                && fingerprint.equals(e.details().get("certificateFingerprintSha256"))).toList();
        AuditEvent latestLogin = logins.isEmpty() ? null : logins.getFirst();
        String pacsSessionId = latestLogin == null ? null : latestLogin.details().get("pacsSessionId");
        List<AuditEvent> extensions = pacsSessionId == null ? List.of() : events.stream().filter(e ->
                "PACS_CERTIFICATE_SESSION_EXTENDED".equals(e.action())
                        && doctorId.equals(e.targetId())
                        && !e.occurredAt().isBefore(latestLogin.occurredAt())
                        && pacsSessionId.equals(e.details().get("pacsSessionId"))
                        && fingerprint.equals(e.details().get("certificateFingerprintSha256"))).toList();
        String expiry = !extensions.isEmpty() ? extensions.getFirst().details().get("expiresAt")
                : latestLogin == null ? null : latestLogin.details().get("expiresAt");
        long attempts = events.stream().filter(e -> doctorId.equals(e.targetId())
                && "CERTIFICATE_LOGIN_ATTEMPTED".equals(e.action())
                && fingerprint.equals(e.details().get("fingerprintSha256"))).count();
        long reportRequests = events.stream().filter(e -> "PACS_REPORT_CERTIFICATE_CHECK".equals(e.action())
                && AuditService.SUCCESS.equals(e.outcome())
                && doctorId.equals(e.targetId())
                && fingerprint.equals(e.details().get("certificateFingerprintSha256"))).count();
        long signedReports = completed.stream().filter(s -> s.doctorEvidence() != null
                && doctorId.equals(s.doctorEvidence().doctorId())
                && fingerprint.equals(s.doctorEvidence().certificateEnrollmentFingerprint())
                && s.doctorEvidence().reportId() != null).count();
        Instant lastRequest = events.stream().filter(e -> "PACS_REPORT_CERTIFICATE_CHECK".equals(e.action())
                && doctorId.equals(e.targetId())
                && fingerprint.equals(e.details().get("certificateFingerprintSha256"))).map(AuditEvent::occurredAt)
                .findFirst().orElse(null);
        return new CertificateRow(doctorId, doctor.name(),
                latestLogin == null ? null : latestLogin.details().get("pacsLoginId"),
                cert.medicalLicenseNumber() == null ? doctor.licenseNumber() : cert.medicalLicenseNumber(),
                cert.specialistQualificationNumber(),
                cert.issuerDn() == null ? cert.provider() : cert.issuerDn(), cert.notAfter(), fingerprint,
                cert.registrationStatus(), attempts, logins.size(), reportRequests, signedReports,
                latestLogin == null ? null : latestLogin.occurredAt(), lastRequest, pacsSessionId,
                expiry == null ? null : Instant.parse(expiry), extensions.size(), institutionNames);
    }

    private static boolean testCertificate(CertificateDtos.EnrollmentResponse cert) {
        String issuer = cert.issuerDn() == null ? "" : cert.issuerDn().toUpperCase(java.util.Locale.ROOT);
        return "MOCK_CERTIFICATE".equals(cert.kind()) || issuer.contains("SYNTHETIC") || issuer.contains("MOCK CA");
    }

    private static boolean testDoctor(DoctorResponse doctor) {
        String name = doctor.name() == null ? "" : doctor.name().toUpperCase(java.util.Locale.ROOT);
        String center = doctor.centerName() == null ? "" : doctor.centerName().toUpperCase(java.util.Locale.ROOT);
        return name.startsWith("E2E") || name.startsWith("SYNTHETIC") || center.startsWith("SYNTHETIC");
    }
}
