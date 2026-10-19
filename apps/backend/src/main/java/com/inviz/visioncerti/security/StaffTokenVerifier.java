package com.inviz.visioncerti.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Verifies staff bearer tokens: a JWT signed by a key from the server-configured JWKS (RS256, ES256 or PS256 only),
 * with exactly the configured {@code iss}, the configured audience among {@code aud}, a {@code sub}, and valid
 * {@code exp}/{@code nbf}/{@code iat} (60 s skew, at most {@link #MAX_LIFETIME} lifetime). Nothing else in the token
 * (name, groups, roles) grants ongoing authorization: that comes only from the explicit (issuer, subject) mapping.
 * Verified Google email, provider and auth time may initialize the first administrator once when explicitly configured.
 */
public class StaffTokenVerifier {

    static final Duration MAX_SKEW = Duration.ofSeconds(60);
    static final Duration MAX_LIFETIME = Duration.ofHours(24);
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256,
            JWSAlgorithm.PS256);

    /** A verified token: who the identity provider says the caller is. */
    public record VerifiedStaffToken(String issuer, String subject, Instant expiresAt, String email,
            boolean emailVerified, String signInProvider, Instant authTime) {
        public boolean isRecentVerifiedGoogleAccount(String expectedEmail, Instant now) {
            return expectedEmail != null && email != null && email.equalsIgnoreCase(expectedEmail.strip())
                    && emailVerified && "google.com".equals(signInProvider) && authTime != null
                    && !authTime.isAfter(now.plus(MAX_SKEW))
                    && !authTime.isBefore(now.minus(Duration.ofMinutes(10)));
        }
    }

    /** Rejection; {@code reason} is a short code for audit, never token content. */
    public static final class StaffTokenException extends RuntimeException {
        private final String reason;
        private final boolean unavailable;

        StaffTokenException(String reason, boolean unavailable) {
            super("staff token rejected: " + reason, null, false, false);
            this.reason = reason;
            this.unavailable = unavailable;
        }

        public String reason() {
            return reason;
        }

        /** True when the key source (JWKS) could not be reached: a server-side outage, not a bad token. */
        public boolean unavailable() {
            return unavailable;
        }
    }

    private final String issuer;
    private final String audience;
    private final JWKSource<SecurityContext> keys;
    private final Clock clock;

    public StaffTokenVerifier(String issuer, String audience, JWKSource<SecurityContext> keys, Clock clock) {
        this.issuer = issuer;
        this.audience = audience;
        this.keys = keys;
        this.clock = clock;
    }

    /** Production wiring: remote JWKS (Nimbus caching, refresh on unknown kid, retry once). */
    public static StaffTokenVerifier remote(String issuer, String audience, String jwksUri, Clock clock) {
        try {
            JWKSource<SecurityContext> source = JWKSourceBuilder
                    .<SecurityContext>create(URI.create(jwksUri).toURL())
                    .retrying(true)
                    .build();
            return new StaffTokenVerifier(issuer, audience, source, clock);
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_STAFF_OIDC_JWKS_URI is not a valid URL", e);
        }
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }

    public VerifiedStaffToken verify(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new StaffTokenException("MALFORMED", false);
        }
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT,
                new JOSEObjectType("at+jwt"), new JOSEObjectType("application/at+jwt"), null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(Set.of(audience),
                new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("iss", "sub", "aud", "exp", "iat"), null) {
            @Override
            protected Date currentTime() {
                return Date.from(clock.instant());
            }
        };
        claimsVerifier.setMaxClockSkew((int) MAX_SKEW.toSeconds());
        processor.setJWTClaimsSetVerifier(claimsVerifier);
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (KeySourceException e) {
            throw new StaffTokenException("JWKS_UNAVAILABLE", true);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new StaffTokenException("REJECTED", false);
        }
        Instant now = clock.instant();
        Date iat = claims.getIssueTime();
        Date exp = claims.getExpirationTime();
        if (iat == null || exp == null || iat.toInstant().isAfter(now.plus(MAX_SKEW))
                || Duration.between(iat.toInstant(), exp.toInstant()).compareTo(MAX_LIFETIME) > 0) {
            throw new StaffTokenException("LIFETIME", false);
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new StaffTokenException("SUBJECT", false);
        }
        Object firebaseClaim = claims.getClaim("firebase");
        Object provider = firebaseClaim instanceof Map<?, ?> firebase ? firebase.get("sign_in_provider") : null;
        Object authTime = claims.getClaim("auth_time");
        Instant signedInAt = authTime instanceof Number number ? Instant.ofEpochSecond(number.longValue()) : null;
        return new VerifiedStaffToken(claims.getIssuer(), subject, exp.toInstant(),
                claims.getClaim("email") instanceof String email ? email : null,
                Boolean.TRUE.equals(claims.getClaim("email_verified")),
                provider instanceof String value ? value : null, signedInAt);
    }
}
