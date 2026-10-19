package com.inviz.visioncerti.signing;

/**
 * Signing capability of one authenticated physician session. Implementations re-check the physician's state,
 * tenant membership and certificate every time {@link #assertCanSign()} is called.
 */
public interface RegisteredDoctorSigner {

    String tenantId();

    String doctorId();

    /** Throws an {@code ApiException} unless the physician may sign right now. */
    SignerSnapshot assertCanSign();

    /** Returns a DER ECDSA signature over the given SHA-256 digest using the pinned key version. */
    byte[] signDigest(SignerSnapshot snapshot, byte[] sha256Digest);

    /**
     * Re-checks, after the backend returned a signature, that the physician is still ACTIVE with the same
     * unrevoked key and certificate. Closes the race with a suspension or revocation during signing.
     */
    void confirmStillAuthorized(SignerSnapshot snapshot);

    /** What was checked and which key/certificate will sign; copied into {@link DoctorSignatureEvidence}. */
    record SignerSnapshot(
            String doctorId,
            String identityMode,
            String identityIssuer,
            String identitySubject,
            String signingBackend,
            String signatureMode,
            boolean cryptographicallyAttributable,
            boolean revocationChecked,
            String certificateId,
            String certificateKind,
            String keyName,
            int keyVersion,
            String publicKeySpki,
            String certificateEnrollmentId,
            String certificateEnrollmentFingerprint) {
    }
}
