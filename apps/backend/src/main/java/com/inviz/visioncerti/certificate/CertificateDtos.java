package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shapes for {@code /v1/certificate-enrollment} and the admin certificate routes. Secrets are redacted in toString. */
public final class CertificateDtos {

    private CertificateDtos() {
    }

    public record PcCertificateConfig(boolean trustConfigured, int trustAnchorCount, int crlCount,
            List<String> allowedSignatureAlgorithms, String revocation, boolean ocspSupported) {
    }

    public record MobileConfig(boolean configured, String provider) {
    }

    public record ConfigResponse(String activationPolicy, String serviceId, long challengeTtlSeconds,
            PcCertificateConfig pcCertificate, MobileConfig mobile, boolean devMockCertificateEnabled,
            boolean clinicalUseAllowed) {
    }

    public record EnrollmentChallengeRequest(String invitationCode) {
        @Override
        public String toString() {
            return "EnrollmentChallengeRequest[<redacted>]";
        }
    }

    public record ChallengeResponse(
            UUID challengeId,
            UUID registrationId,
            String purpose,
            UUID doctorId,
            String tenantId,
            String serviceId,
            String nonce,
            Instant issuedAt,
            Instant expiresAt,
            String signedPayload,
            String signedPayloadBase64,
            List<String> allowedSignatureAlgorithms) {
    }

    public record VerifyRequest(String registrationId, String certificate, List<String> chain, String signature,
            String signatureAlgorithm) {
    }

    public record LoginRequest(String challengeId, String fingerprintSha256, String certificate, List<String> chain,
            String signature, String signatureAlgorithm) {
    }

    public record EnrollmentResponse(
            UUID registrationId,
            UUID doctorId,
            String tenantId,
            String kind,
            String registrationStatus,
            String externalStatus,
            Instant externalCheckedAt,
            String reviewStatus,
            String reviewMethod,
            String reviewedBy,
            Instant reviewedAt,
            Instant consentedAt,
            String fingerprintSha256,
            String publicKeySha256,
            String subjectDn,
            String issuerDn,
            String serialNumber,
            Instant notBefore,
            Instant notAfter,
            String signatureAlgorithm,
            String keyAlgorithm,
            String provider,
            String providerSubject,
            Instant createdAt,
            Instant activatedAt,
            Instant revokedAt,
            String revocationReason,
            UUID supersedes) {
    }

    public record CertificateSessionResponse(EnrollmentResponse enrollment, String activationToken, Instant expiresAt,
            String identityMode, DoctorResponse doctor) {
        @Override
        public String toString() {
            return "CertificateSessionResponse[enrollment=" + enrollment.registrationId()
                    + ", activationToken=<redacted>, identityMode=" + identityMode + "]";
        }
    }

    public record UnregisterRequest(String reason) {
    }

    public record ReviewRequest(String registrationId, String decision, String fingerprintSha256, String evidenceRef,
            String note) {
    }

    public record MobileStartRequest(String purpose, String invitationCode) {
        @Override
        public String toString() {
            return "MobileStartRequest[purpose=" + purpose + ", <redacted>]";
        }
    }

    public record MobileStartResponse(UUID transactionId, String purpose, String provider, Instant expiresAt,
            String transactionSecret, Map<String, Object> providerStart) {
        @Override
        public String toString() {
            return "MobileStartResponse[transactionId=" + transactionId + ", transactionSecret=<redacted>]";
        }
    }

    public record MobileCallbackResponse(UUID transactionId, String status) {
    }

    public record MobileConsumeRequest(String transactionSecret) {
        @Override
        public String toString() {
            return "MobileConsumeRequest[<redacted>]";
        }
    }

    public record DevMockSignRequest(String challengeId, String fingerprintSha256) {
    }
}
