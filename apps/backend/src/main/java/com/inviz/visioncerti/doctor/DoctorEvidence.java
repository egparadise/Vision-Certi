package com.inviz.visioncerti.doctor;

import java.time.Instant;
import java.util.UUID;

/** Immutable, append-only evidence records. None of them contains codes, nonces, tokens or ID tokens. */
public sealed interface DoctorEvidence {

    UUID doctorId();

    String tenantId();

    /** The admin attests having reviewed identity and license evidence (opaque references only). */
    record AdminVerification(String type, UUID id, UUID doctorId, String tenantId, String adminActorId,
            String identityEvidenceRef, String licenseEvidenceRef, Instant at) implements DoctorEvidence {
        AdminVerification(UUID id, UUID doctorId, String tenantId, String adminActorId, String identityEvidenceRef,
                String licenseEvidenceRef, Instant at) {
            this("ADMIN_VERIFICATION", id, doctorId, tenantId, adminActorId, identityEvidenceRef, licenseEvidenceRef,
                    at);
        }
    }

    /** A verified identity assertion (Google or dev MOCK_IDENTITY) used for activation or login. */
    record LoginEvidence(String type, UUID id, UUID doctorId, String tenantId, String identityMode, String issuer,
            String subject, String purpose, UUID challengeId, Instant at) implements DoctorEvidence {
        LoginEvidence(UUID id, UUID doctorId, String tenantId, String identityMode, String issuer, String subject,
                String purpose, UUID challengeId, Instant at) {
            this("LOGIN_EVIDENCE", id, doctorId, tenantId, identityMode, issuer, subject, purpose, challengeId, at);
        }
    }

    /** The physician's own consent, given through their own session. */
    record ConsentEvidence(String type, UUID id, UUID doctorId, String tenantId, String version,
            boolean authentication, boolean signature, boolean privacy, String identityMode, Instant at)
            implements DoctorEvidence {
        ConsentEvidence(UUID id, UUID doctorId, String tenantId, String version, String identityMode, Instant at) {
            this("CONSENT", id, doctorId, tenantId, version, true, true, true, identityMode, at);
        }
    }
}
