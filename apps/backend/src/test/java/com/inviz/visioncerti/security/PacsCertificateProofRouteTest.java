package com.inviz.visioncerti.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PacsCertificateProofRouteTest {
    @Test
    void onlyExactCertificateProofPostsArePublicInProductionAndDevelopment() {
        for (String path : new String[]{"/v1/pacs-certificate-proofs/challenges",
                "/v1/pacs-certificate-proofs/verify"}) {
            var post = new MockHttpServletRequest("POST", path);
            var get = new MockHttpServletRequest("GET", path);
            assertThat(DevHeaderAuthFilter.isPacsCertificateProofPublicRoute(post, path)).isTrue();
            assertThat(StaffAuthFilter.isPublicRoute(post, path)).isTrue();
            assertThat(DevHeaderAuthFilter.isPacsCertificateProofPublicRoute(get, path)).isFalse();
            assertThat(StaffAuthFilter.isPublicRoute(get, path)).isFalse();
        }
        String lookalike = "/v1/pacs-certificate-proofs/verify/other";
        var post = new MockHttpServletRequest("POST", lookalike);
        assertThat(StaffAuthFilter.isPublicRoute(post, lookalike)).isFalse();
    }
}
