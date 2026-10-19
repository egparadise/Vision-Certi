package com.inviz.visioncerti.doctor.credential;

/** Provisioning or signing failure. Messages never contain tokens or key material. */
public class CredentialException extends RuntimeException {

    public enum Kind {
        /** Backend not selected or required settings missing; retrying will not help. */
        NOT_CONFIGURED,
        /** Network/HTTP failure or unexpected response; retry may help. */
        UNAVAILABLE,
        /** Certificate public key differs from the key that will sign. */
        KEY_MISMATCH,
        /** Key exists but is not a non-exportable ECDSA P-256 key. */
        KEY_POLICY_VIOLATION,
        /** Certificate revoked or outside its validity. */
        CERTIFICATE_NOT_VALID
    }

    private final Kind kind;

    public CredentialException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CredentialException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
