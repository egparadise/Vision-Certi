package com.inviz.visioncerti.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
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

/** Test OIDC provider: mints RS256 staff tokens signed by a local key that is published as an in-memory JWKS. */
public final class TestOidc {

    public static final String ISSUER = "https://idp.test.invalid/realms/vision-certi";
    public static final String AUDIENCE = "vision-certi-api";
    public static final String CLIENT_ID = "vision-certi-web";

    private final RSAKey key;
    private final RSAKey unpublished;
    private final Clock clock;

    public TestOidc(Clock clock) {
        this.clock = clock;
        try {
            key = new RSAKeyGenerator(2048).keyID("staff-test-1").generate();
            unpublished = new RSAKeyGenerator(2048).keyID("staff-test-1").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public JWKSource<SecurityContext> jwks() {
        return new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));
    }

    public StaffTokenVerifier verifier() {
        return new StaffTokenVerifier(ISSUER, AUDIENCE, jwks(), clock);
    }

    /** A verifier whose key source is down (JWKS endpoint unreachable). */
    public StaffTokenVerifier unreachableVerifier() {
        return new StaffTokenVerifier(ISSUER, AUDIENCE, (selector, context) -> {
            throw new KeySourceException("JWKS endpoint unreachable");
        }, clock);
    }

    public JWTClaimsSet.Builder claims(String subject) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
    }

    public String token(String subject) {
        return sign(claims(subject).build());
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, key);
    }

    public String signWithUnpublishedKey(JWTClaimsSet claims) {
        return sign(claims, unpublished);
    }

    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    public static String hmac(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                    .build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
