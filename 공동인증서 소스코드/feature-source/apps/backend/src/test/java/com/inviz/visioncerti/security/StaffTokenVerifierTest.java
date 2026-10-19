package com.inviz.visioncerti.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.security.StaffTokenVerifier.StaffTokenException;
import com.inviz.visioncerti.store.MemoryStore;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Staff token verification rules and the filter's outage behaviour (unit level, no Spring context). */
class StaffTokenVerifierTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T03:00:00Z"));
    private final TestOidc oidc = new TestOidc(clock);
    private final StaffTokenVerifier verifier = oidc.verifier();

    @Test
    void acceptsOnlyTheConfiguredIssuerAudienceAndSignatureWithinLifetime() {
        assertThat(verifier.verify(oidc.token("subject-1")).subject()).isEqualTo("subject-1");
        assertThat(verifier.verify(oidc.token("subject-1")).issuer()).isEqualTo(TestOidc.ISSUER);
        assertRejected(oidc.sign(oidc.claims("s").audience("someone-else").build()));
        assertRejected(oidc.sign(oidc.claims("s").issuer(TestOidc.ISSUER + "/").build()));
        assertRejected(oidc.signWithUnpublishedKey(oidc.claims("s").build()));
        assertRejected(TestOidc.hmac(oidc.claims("s").build()));
        assertRejected(TestOidc.unsigned(oidc.claims("s").build()));
        assertRejected(oidc.sign(oidc.claims("s").issueTime(null).build()));
        assertRejected(oidc.sign(oidc.claims("s").notBeforeTime(Date.from(clock.instant().plusSeconds(600)))
                .build()));
        assertRejected(oidc.sign(oidc.claims("s").expirationTime(Date.from(clock.instant().plus(Duration.ofHours(25))))
                .build()));
        assertRejected("not-a-jwt");
        String token = oidc.token("subject-1");
        clock.advance(Duration.ofMinutes(7)); // 5 min lifetime + 60 s skew
        assertRejected(token);
    }

    @Test
    void identityProviderOutageIsReportedAsUnavailableNotAsABadToken() throws Exception {
        assertThatThrownBy(() -> oidc.unreachableVerifier().verify(oidc.token("subject-1")))
                .isInstanceOfSatisfying(StaffTokenException.class, e -> assertThat(e.unavailable()).isTrue());

        StaffDirectory directory = new StaffDirectory(new MemoryStore(), new AuditService(clock), clock,
                TestOidc.ISSUER);
        StaffAuthFilter filter = new StaffAuthFilter(oidc.unreachableVerifier(), directory);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/staff/me");
        request.addHeader("Authorization", "Bearer " + oidc.token("subject-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean();
        FilterChain chain = (req, res) -> passed.set(true);
        filter.doFilter(request, response, chain);
        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("STAFF_AUTH_UNAVAILABLE").doesNotContain("subject-1");
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(StaffTokenException.class, e -> assertThat(e.unavailable()).isFalse());
    }
}
