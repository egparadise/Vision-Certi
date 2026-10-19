package com.inviz.visioncerti.doctor.credential;

import com.inviz.visioncerti.common.Hashing;
import java.time.Instant;
import java.util.Base64;

/**
 * Public material for one physician's signing key: key reference, pinned version, SubjectPublicKeyInfo and the
 * certificate (X.509 from OpenBao PKI, or a labeled simulated descriptor). Never contains private key material.
 */
public record DoctorCredential(
        String backend,
        String keyName,
        int keyVersion,
        String publicKeySpki,
        String certificateKind,
        String certificatePem,
        String serialNumber,
        String subject,
        String issuer,
        Instant notBefore,
        Instant notAfter) {

    public static final String KIND_X509 = "X509";
    public static final String KIND_SIMULATED = "SIMULATED_NOT_X509";

    public byte[] publicKeyDer() {
        return Base64.getDecoder().decode(publicKeySpki);
    }

    public String publicKeySha256() {
        return Hashing.sha256Hex(publicKeyDer());
    }

    public boolean withinValidity(Instant now) {
        return !now.isBefore(notBefore) && now.isBefore(notAfter);
    }
}
