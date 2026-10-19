package com.inviz.visioncerti.signing;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY mock signer. Computes HMAC-SHA256 with a random key generated in memory at startup
 * and never written anywhere; it is discarded on restart. This is not an electronic signature: it has no
 * certificate, no signer-held key, no HSM, no timestamp and no legal or clinical meaning. Every value it
 * produces is labeled {@value #MODE}.
 */
@Component
@Profile("dev")
public class MockSigningProvider implements SigningProvider {

    public static final String MODE = "MOCK_NOT_VALID_FOR_CLINICAL_USE";
    private static final String ALGORITHM = "MOCK-HMAC-SHA256";

    private final SecretKeySpec key;

    public MockSigningProvider() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    @Override
    public String signatureMode() {
        return MODE;
    }

    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    @Override
    public boolean clinicalUseAllowed() {
        return false;
    }

    @Override
    public String sign(byte[] payload) {
        return Base64.getEncoder().encodeToString(mac(payload));
    }

    @Override
    public Check verify(byte[] payload, String signatureValue) {
        byte[] presented;
        try {
            presented = Base64.getDecoder().decode(signatureValue);
        } catch (IllegalArgumentException e) {
            return Check.BROKEN;
        }
        return MessageDigest.isEqual(mac(payload), presented) ? Check.INTACT : Check.BROKEN;
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
