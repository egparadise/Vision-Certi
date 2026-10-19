package com.inviz.visioncerti.enrollment;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/** PBKDF2-HMAC-SHA256 with 600,000 iterations and a random 16-byte salt (OWASP Password Storage Cheat Sheet). */
@Component
public class PasswordHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public PasswordHash hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new PasswordHash(ALGORITHM, ITERATIONS, salt, derive(password, salt, ITERATIONS));
    }

    public boolean matches(String password, PasswordHash stored) {
        if (password == null || stored == null) {
            return false;
        }
        return MessageDigest.isEqual(derive(password, stored.salt(), stored.iterations()), stored.hash());
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    /** Stored password verifier. Byte arrays are copied in and out. */
    public record PasswordHash(String algorithm, int iterations, byte[] salt, byte[] hash) {

        public PasswordHash {
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        @Override
        public String toString() {
            return "PasswordHash[algorithm=" + algorithm + ", iterations=" + iterations + ", <redacted>]";
        }
    }
}
