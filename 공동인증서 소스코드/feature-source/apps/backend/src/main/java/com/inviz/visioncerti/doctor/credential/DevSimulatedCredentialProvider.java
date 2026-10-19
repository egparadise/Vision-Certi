package com.inviz.visioncerti.doctor.credential;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * DEVELOPMENT ONLY, explicit opt-in ({@code VISIONCERTI_SIGNING_BACKEND=DEV_SIMULATED}). Keeps an ECDSA P-256 key per
 * physician in this JVM's memory (lost on restart) and returns a labeled descriptor instead of an X.509 certificate.
 * It exists so the UI flow can be exercised without OpenBao; its signatures prove nothing about who held the key.
 */
public class DevSimulatedCredentialProvider implements DoctorCredentialProvider {

    public static final String SIGNATURE_MODE = "DEV_SIMULATED_ECDSA_NOT_VALID_FOR_CLINICAL_USE";

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, KeyPair> keys = new HashMap<>();
    private final Set<String> revokedSerials = new HashSet<>();

    public DevSimulatedCredentialProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String backend() {
        return DEV_SIMULATED;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public String signatureMode() {
        return SIGNATURE_MODE;
    }

    @Override
    public boolean cryptographicallyAttributable() {
        return false;
    }

    @Override
    public boolean checksRevocationRemotely() {
        return false;
    }

    @Override
    public synchronized DoctorCredential provision(String doctorId) {
        String keyName = "dev-sim-" + doctorId;
        KeyPair pair = keys.computeIfAbsent(keyName, k -> generate());
        byte[] serial = new byte[16];
        random.nextBytes(serial);
        Instant now = clock.instant();
        return new DoctorCredential(DEV_SIMULATED, keyName, 1,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), DoctorCredential.KIND_SIMULATED,
                null, "SIM-" + HexFormat.of().formatHex(serial), "SIMULATED CN=" + keyName,
                "Vision-Certi DEV SIMULATED (not a CA, not X.509)", now, now.plus(Duration.ofDays(365)));
    }

    @Override
    public synchronized byte[] signDigest(DoctorCredential credential, byte[] sha256Digest) {
        KeyPair pair = keys.get(credential.keyName());
        if (pair == null) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                    "Simulated key is gone (process restarted); activation must be repeated");
        }
        try {
            Signature signer = Signature.getInstance("NONEwithECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(sha256Digest);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Simulated signing failed", e);
        }
    }

    @Override
    public synchronized void assertNotRevoked(DoctorCredential credential) {
        if (revokedSerials.contains(credential.serialNumber())) {
            throw new CredentialException(CredentialException.Kind.CERTIFICATE_NOT_VALID, "Certificate revoked");
        }
    }

    @Override
    public synchronized void revoke(DoctorCredential credential) {
        revokedSerials.add(credential.serialNumber());
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC P-256 unavailable", e);
        }
    }
}
