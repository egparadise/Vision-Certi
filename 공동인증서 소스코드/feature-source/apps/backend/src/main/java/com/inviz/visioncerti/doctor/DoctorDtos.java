package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.doctor.credential.DoctorCredential;
import java.time.Instant;
import java.util.UUID;

/** Shapes for {@code /v1/admin/doctors}, {@code /v1/doctor-activation} and {@code /v1/doctor}. Secrets are redacted in toString. */
public final class DoctorDtos {

    private DoctorDtos() {
    }

    public record PreregisterDoctor(String name, String licenseNumber, String centerName, String googleEmail,
            String identityEvidenceRef, String licenseEvidenceRef, String expectedCertificateFingerprint) {

        public PreregisterDoctor(String name, String licenseNumber, String centerName, String googleEmail,
                String identityEvidenceRef, String licenseEvidenceRef) {
            this(name, licenseNumber, centerName, googleEmail, identityEvidenceRef, licenseEvidenceRef, null);
        }

        @Override
        public String toString() {
            return "PreregisterDoctor[<personal data redacted>]";
        }
    }

    public record PreregistrationResponse(DoctorResponse doctor, String invitationCode, Instant invitationExpiresAt) {
        @Override
        public String toString() {
            return "PreregistrationResponse[doctor=" + doctor.id() + ", invitationCode=<redacted>, invitationExpiresAt="
                    + invitationExpiresAt + "]";
        }
    }

    public record ChangeDoctorStatus(String status, String reason) {
    }

    public record CertificateResponse(
            String certificateId,
            String kind,
            String backend,
            String subject,
            String issuer,
            String serialNumber,
            Instant notBefore,
            Instant notAfter,
            String publicKeySha256,
            String keyName,
            int keyVersion,
            String status,
            String certificatePem) {

        static CertificateResponse of(DoctorCredential c, String status) {
            return new CertificateResponse(c.serialNumber(), c.certificateKind(), c.backend(), c.subject(), c.issuer(),
                    c.serialNumber(), c.notBefore(), c.notAfter(), c.publicKeySha256(), c.keyName(), c.keyVersion(),
                    status, c.certificatePem());
        }
    }

    public record DoctorResponse(
            UUID id,
            String tenantId,
            String name,
            String licenseNumber,
            String centerName,
            String googleEmail,
            DoctorStatus status,
            String identityMode,
            boolean googleAccountLinked,
            String invitationState,
            Instant invitationExpiresAt,
            Instant identityVerifiedAt,
            Instant consentedAt,
            String consentVersion,
            Instant activatedAt,
            String statusReason,
            Instant statusChangedAt,
            Instant createdAt,
            String createdBy,
            String signingBackend,
            CertificateResponse certificate,
            String expectedCertificateFingerprint) {
        @Override
        public String toString() {
            return "DoctorResponse[id=" + id + ", status=" + status + ", <personal data redacted>]";
        }
    }

    public record CreateActivationChallenge(String invitationCode) {
        @Override
        public String toString() {
            return "CreateActivationChallenge[invitationCode=<redacted>]";
        }
    }

    public record ChallengeResponse(String challengeId, String nonce, Instant expiresAt, String purpose) {
        @Override
        public String toString() {
            return "ChallengeResponse[challengeId=" + challengeId + ", nonce=<redacted>, purpose=" + purpose + "]";
        }
    }

    public record GoogleIdentityRequest(String challengeId, String idToken) {
        @Override
        public String toString() {
            return "GoogleIdentityRequest[challengeId=" + challengeId + ", idToken=<redacted>]";
        }
    }

    public record DevIdentityRequest(String challengeId, String email, String subject) {
        @Override
        public String toString() {
            return "DevIdentityRequest[challengeId=" + challengeId + ", <redacted>]";
        }
    }

    public record SessionResponse(String activationToken, Instant expiresAt, String identityMode,
            DoctorResponse doctor) {
        @Override
        public String toString() {
            return "SessionResponse[activationToken=<redacted>, expiresAt=" + expiresAt + ", identityMode="
                    + identityMode + "]";
        }
    }

    public record ConsentRequest(String version, Boolean authentication, Boolean signature, Boolean privacy) {
    }

    public record MeResponse(DoctorResponse doctor, CertificateResponse certificate, String identityMode,
            Instant sessionExpiresAt) {
    }

    public record ConfigResponse(String googleClientId, boolean googleConfigured, boolean devSimulationEnabled,
            String signingBackend, boolean signingBackendAvailable, boolean clinicalUseAllowed,
            String activationPolicy, boolean adminInvitationsEnabled) {
    }
}
