package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.signing.DoctorSignatureEvidence;
import java.time.Instant;
import java.util.UUID;

/** Shapes of the PACS integration routes (docs/SV70_PACS_INTEGRATION.md). Secrets are redacted in toString. */
public final class PacsIntegrationDtos {

    private PacsIntegrationDtos() {
    }

    public record EnrollmentStartRequest(String tenantId, String pacsUserId, String name,
            String licenseNumber, String centerName) {
        @Override public String toString() { return "EnrollmentStartRequest[<personal data redacted>]"; }
    }

    public record EnrollmentStatusRequest(String tenantId, String pacsUserId) { }

    public record EnrollmentStartResponse(UUID doctorId,
            com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse challenge) { }

    public record EnrollmentStatusResponse(UUID doctorId, String registrationStatus,
            UUID certificateEnrollmentId, String fingerprintSha256, String doctorLicenseNumber) { }

    public record HandoffRequest(String audience) {
    }

    public record HandoffResponse(String handoffCode, Instant expiresAt, String audience, String tenantId) {
        @Override
        public String toString() {
            return "HandoffResponse[handoffCode=<redacted>, expiresAt=" + expiresAt + ", audience=" + audience + "]";
        }
    }

    public record RedeemRequest(String handoffCode) {
        @Override
        public String toString() {
            return "RedeemRequest[<redacted>]";
        }
    }

    public record VerifiedIdentityResponse(String tenantId, UUID doctorId, String doctorStatus, String identityMode,
            UUID certificateEnrollmentId, String certificateFingerprintSha256, Instant authenticatedAt,
            Instant sessionExpiresAt, Instant verifiedAt, boolean activeSigner, String doctorLicenseNumber) {
    }

    public record ReportSignRequestCreate(String tenantId, String doctorId, String externalReportId,
            String externalStudyId, Integer revision, String title, String contentType, String contentBase64,
            String contentSha256) {
        @Override
        public String toString() {
            return "ReportSignRequestCreate[tenantId=" + tenantId + ", externalReportId=" + externalReportId
                    + ", revision=" + revision + ", content=<omitted>]";
        }
    }

    public record ReportSignRequestCreated(UUID signRequestId, UUID documentId, UUID documentVersionId,
            String documentSha256, String signerId, String status, Instant expiresAt, int revision) {
    }

    public record SignatureView(UUID signatureId, String algorithm, String signatureMode, String signatureValueBase64,
            Instant signedAt, String documentSha256, DoctorSignatureEvidence doctorEvidence) {
    }

    public record ReportSignRequestStatus(UUID signRequestId, String tenantId, String status, UUID documentVersionId,
            String documentSha256, String signerId, Instant expiresAt, Instant approvedAt, SignatureView signature) {
    }
}
