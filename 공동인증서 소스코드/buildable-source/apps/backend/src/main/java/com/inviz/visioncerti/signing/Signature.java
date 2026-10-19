package com.inviz.visioncerti.signing;

import java.time.Instant;
import java.util.UUID;

/**
 * Stored signature record. Legacy synthetic signatures use {@code MOCK_NOT_VALID_FOR_CLINICAL_USE} and have no
 * {@code doctorEvidence}; registered physician signatures carry {@link DoctorSignatureEvidence}.
 */
public record Signature(
        UUID id,
        String tenantId,
        UUID signRequestId,
        UUID documentId,
        UUID documentVersionId,
        String documentSha256,
        String signerId,
        String signatureMode,
        String algorithm,
        String signatureValue,
        boolean clinicalUseAllowed,
        Instant createdAt,
        DoctorSignatureEvidence doctorEvidence) {
}
