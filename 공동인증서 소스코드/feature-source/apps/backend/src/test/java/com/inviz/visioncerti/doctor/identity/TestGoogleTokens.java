package com.inviz.visioncerti.doctor.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Test issuer that mints RS256 ID tokens shaped like Google's, signed by a local key published as a JWKS. */
public final class TestGoogleTokens {

    public static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

    private final RSAKey key;
    private final RSAKey otherKey;
    private final Clock clock;

    public TestGoogleTokens(Clock clock) {
        this.clock = clock;
        try {
            key = new RSAKeyGenerator(2048).keyID("google-test-1").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("google-test-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public JWKSource<SecurityContext> jwks() {
        return new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
    }

    public GoogleIdTokenVerifier verifier() {
        return new GoogleIdTokenVerifier(CLIENT_ID, jwks(), clock);
    }

    public JWTClaimsSet.Builder claims(String subject, String email, String nonce) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .subject(subject)
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))))
                .claim("email", email)
                .claim("email_verified", true)
                .claim("nonce", nonce);
    }

    public String token(String subject, String email, String nonce) {
        return sign(claims(subject, email, nonce).build());
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, key);
    }

    public String signWithUnpublishedKey(JWTClaimsSet claims) {
        return sign(claims, otherKey);
    }

    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    public static String hmac(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
