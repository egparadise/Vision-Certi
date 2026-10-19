package com.inviz.visioncerti.signing;

import java.time.Instant;
import java.util.UUID;

/**
 * Binding metadata of a registered physician's signature. {@code signatureFormat} is a raw ECDSA signature over
 * the SHA-256 of the stored document bytes; it is not CAdES/PAdES and carries no trusted timestamp.
 */
public record DoctorSignatureEvidence(
        String doctorId,
        String identityMode,
        String identityIssuer,
        String identitySubject,
        String certificateId,
        String certificateKind,
        String keyName,
        int keyVersion,
        UUID approvalId,
        String reportId,
        UUID documentVersionId,
        String documentSha256,
        Instant signedAt,
        UUID auditId,
        String signatureFormat,
        String signingBackend,
        boolean cryptographicallyAttributable,
        boolean revocationCheckedAtSigning,
        String publicKeySpki,
        String certificateEnrollmentId,
        String certificateEnrollmentFingerprint,
        String externalCertificateDerBase64,
        String externalSignatureBase64,
        String externalSignatureAlgorithm,
        String externalSignedPayloadBase64) {

    public static final String FORMAT = "RAW_ECDSA_P256_SHA256_DER";
}
