package com.inviz.visioncerti.signing;

/**
 * Produces and checks signature values. The only implementation in this slice is
 * {@link MockSigningProvider}; a production provider (HSM, CAdES, RFC 3161 TSA) does not exist yet.
 */
public interface SigningProvider {

    enum Check {
        /** Value matches the payload under this provider's key. */
        INTACT,
        /** Value does not match: payload or value was altered. */
        BROKEN,
        /** Provider cannot decide (e.g. key no longer available). */
        UNVERIFIABLE
    }

    /** Label stored on every signature, e.g. {@code MOCK_NOT_VALID_FOR_CLINICAL_USE}. */
    String signatureMode();

    String algorithm();

    /** Whether signatures from this provider may ever be used clinically. */
    boolean clinicalUseAllowed();

    String sign(byte[] payload);

    Check verify(byte[] payload, String signatureValue);
}
