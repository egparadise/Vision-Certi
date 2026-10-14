package com.inviz.visioncerti.doctor.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The verifier checks signature, issuer, audience, expiry, issued-at, nonce and email_verified. */
class GoogleIdTokenVerifierTest {

    private static final String NONCE = "nonce-abc";

    private MutableClock clock;
    private TestGoogleTokens google;
    private GoogleIdTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        google = new TestGoogleTokens(clock);
        verifier = google.verifier();
    }

    private void rejected(String token, String reason) {
        assertThatThrownBy(() -> verifier.verify(token, NONCE))
                .isInstanceOf(GoogleIdTokenVerifier.IdTokenException.class)
                .satisfies(t -> assertThat(((GoogleIdTokenVerifier.IdTokenException) t).reason()).isEqualTo(reason));
    }

    @Test
    void acceptsValidTokenAndNormalizesIdentity() {
        VerifiedIdentity identity = verifier.verify(google.token("sub-123", "Dr.Kim@Example.com", NONCE), NONCE);
        assertThat(identity.mode()).isEqualTo("GOOGLE");
        assertThat(identity.issuer()).isEqualTo("https://accounts.google.com");
        assertThat(identity.subject()).isEqualTo("sub-123");
        assertThat(identity.email()).isEqualTo("dr.kim@example.com");
        assertThat(identity.toString()).doesNotContain("sub-123").doesNotContain("example.com");

        // The short issuer form is accepted and canonicalized to the same binding.
        String shortIss = google.sign(google.claims("sub-123", "a@example.com", NONCE).issuer("accounts.google.com")
                .build());
        assertThat(verifier.verify(shortIss, NONCE).bindingKey()).isEqualTo(identity.bindingKey());
    }

    @Test
    void rejectsWrongNonceAudienceIssuerAndSignature() {
        rejected(google.token("s", "a@example.com", "other-nonce"), "REJECTED");
        rejected(google.sign(google.claims("s", "a@example.com", NONCE).audience("someone-else").build()), "REJECTED");
        rejected(google.sign(google.claims("s", "a@example.com", NONCE).issuer("https://evil.example").build()),
                "ISSUER");
        rejected(google.signWithUnpublishedKey(google.claims("s", "a@example.com", NONCE).build()), "REJECTED");
        rejected(TestGoogleTokens.unsigned(google.claims("s", "a@example.com", NONCE).build()), "REJECTED");
        rejected(TestGoogleTokens.hmac(google.claims("s", "a@example.com", NONCE).build()), "REJECTED");
        rejected("not-a-jwt", "REJECTED");
        rejected("", "MALFORMED");
    }

    @Test
    void rejectsTamperedPayload() {
        String token = google.token("s", "a@example.com", NONCE);
        String[] parts = token.split("\\.");
        String forged = google.token("attacker", "a@example.com", NONCE).split("\\.")[1];
        rejected(parts[0] + "." + forged + "." + parts[2], "REJECTED");
    }

    @Test
    void rejectsExpiredFutureAndUnverifiedEmail() {
        String token = google.token("s", "a@example.com", NONCE);
        clock.advance(Duration.ofMinutes(12));
        rejected(token, "REJECTED");

        JWTClaimsSet future = google.claims("s", "a@example.com", NONCE)
                .issueTime(Date.from(clock.instant().plus(Duration.ofMinutes(5)))).build();
        rejected(google.sign(future), "ISSUED_AT");

        rejected(google.sign(google.claims("s", "a@example.com", NONCE).claim("email_verified", false).build()),
                "EMAIL_NOT_VERIFIED");
        rejected(google.sign(google.claims("s", "a@example.com", NONCE).claim("email_verified", null).build()),
                "REJECTED");
    }

    @Test
    void unconfiguredVerifierRefuses() {
        GoogleIdTokenVerifier none = new GoogleIdTokenVerifier("", google.jwks(), clock);
        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.verify(google.token("s", "a@example.com", NONCE), NONCE))
                .satisfies(t -> assertThat(((GoogleIdTokenVerifier.IdTokenException) t).reason())
                        .isEqualTo("GOOGLE_NOT_CONFIGURED"));
    }
}
