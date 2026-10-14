package com.inviz.visioncerti.doctor.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
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
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies Google ID tokens: RS256 signature against Google's JWKS, issuer, audience (our client id), expiry,
 * issued-at, the challenge nonce and {@code email_verified}. Tokens are never merely decoded, and unsigned or
 * HMAC tokens are rejected by the key selector.
 */
public class GoogleIdTokenVerifier {

    public static final String DEFAULT_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final Duration MAX_SKEW = Duration.ofSeconds(60);
    private static final int MAX_TOKEN_LENGTH = 8192;

    private final String clientId;
    private final JWKSource<SecurityContext> keys;
    private final Clock clock;

    public GoogleIdTokenVerifier(String clientId, JWKSource<SecurityContext> keys, Clock clock) {
        this.clientId = clientId == null || clientId.isBlank() ? null : clientId.trim();
        this.keys = keys;
        this.clock = clock;
    }

    /** Production wiring: remote JWKS with Nimbus caching/refresh. */
    public static GoogleIdTokenVerifier remote(String clientId, String jwksUri, Clock clock) {
        try {
            JWKSource<SecurityContext> source = JWKSourceBuilder
                    .<SecurityContext>create(URI.create(jwksUri).toURL())
                    .retrying(true)
                    .build();
            return new GoogleIdTokenVerifier(clientId, source, clock);
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("VISIONCERTI_GOOGLE_JWKS_URI is not a valid URL", e);
        }
    }

    public boolean configured() {
        return clientId != null;
    }

    public String clientId() {
        return clientId;
    }

    public VerifiedIdentity verify(String idToken, String expectedNonce) {
        if (!configured()) {
            throw new IdTokenException("GOOGLE_NOT_CONFIGURED");
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > MAX_TOKEN_LENGTH || expectedNonce == null) {
            throw new IdTokenException("MALFORMED");
        }
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(clientId,
                new JWTClaimsSet.Builder().claim("nonce", expectedNonce).build(),
                Set.of("iss", "sub", "aud", "exp", "iat", "email", "email_verified", "nonce")) {
            @Override
            protected Date currentTime() {
                return Date.from(clock.instant());
            }
        };
        claimsVerifier.setMaxClockSkew((int) MAX_SKEW.toSeconds());
        processor.setJWTClaimsSetVerifier(claimsVerifier);

        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new IdTokenException("REJECTED");
        }
        if (!ISSUERS.contains(claims.getIssuer())) {
            throw new IdTokenException("ISSUER");
        }
        Date issuedAt = claims.getIssueTime();
        if (issuedAt == null || issuedAt.toInstant().isAfter(clock.instant().plus(MAX_SKEW))) {
            throw new IdTokenException("ISSUED_AT");
        }
        Object verified = claims.getClaim("email_verified");
        if (!(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
            throw new IdTokenException("EMAIL_NOT_VERIFIED");
        }
        String subject = claims.getSubject();
        Object email = claims.getClaim("email");
        if (subject == null || subject.isBlank() || subject.length() > 255 || !(email instanceof String mail)) {
            throw new IdTokenException("CLAIMS");
        }
        return new VerifiedIdentity(VerifiedIdentity.GOOGLE, claims.getIssuer().equals("accounts.google.com")
                ? "https://accounts.google.com" : claims.getIssuer(), subject, mail.toLowerCase(Locale.ROOT));
    }

    /** Rejection; {@code reason} is a short code for audit, never token content. */
    public static final class IdTokenException extends RuntimeException {

        private final String reason;

        public IdTokenException(String reason) {
            super("ID token rejected: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
