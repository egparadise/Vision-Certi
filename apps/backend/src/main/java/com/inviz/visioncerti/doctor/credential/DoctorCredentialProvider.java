package com.inviz.visioncerti.doctor.credential;

/**
 * Creates and uses a per-physician signing key plus certificate. Exactly one implementation is active, chosen by
 * {@code VISIONCERTI_SIGNING_BACKEND}; there is never a fallback from a failing backend to another one.
 */
public interface DoctorCredentialProvider {

    String NOT_CONFIGURED = "NOT_CONFIGURED";
    String OPENBAO_TRANSIT = "OPENBAO_TRANSIT";
    String DEV_SIMULATED = "DEV_SIMULATED";

    String backend();

    boolean available();

    /** Why {@link #available()} is false (no secrets); null when available. */
    String unavailableReason();

    /** Label stored on signatures, always stating that clinical use is not allowed. */
    String signatureMode();

    /** True only when the private key is held outside this process by the configured key service. */
    boolean cryptographicallyAttributable();

    /** True when {@link #assertNotRevoked} consults the issuing CA. */
    boolean checksRevocationRemotely();

    /** Idempotent per physician: a retry reuses the deterministic key and re-checks it. */
    DoctorCredential provision(String doctorId);

    /** DER ECDSA signature over a SHA-256 digest, using {@code credential.keyVersion()}. */
    byte[] signDigest(DoctorCredential credential, byte[] sha256Digest);

    void assertNotRevoked(DoctorCredential credential);

    /** Best effort; local doctor status blocks signing regardless. */
    void revoke(DoctorCredential credential);
}
