package com.inviz.visioncerti.integration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PacsRequestContextTest {
    @Test
    void keepsPeerSeparateFromUntrustedForwardedHeadersAndDoesNotCaptureTokens() {
        var request = new MockHttpServletRequest("POST", "/v1/pacs-certificate-proofs/verify");
        request.setRemoteAddr("10.0.0.1");
        request.setQueryString("token=never-record");
        request.addHeader("Authorization", "Bearer never-record");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 2001:db8::1");
        request.addHeader("Origin", "https://vision-certi.web.app/private?token=never-record");
        request.addHeader("User-Agent", "x".repeat(1000));
        var values = PacsRequestContext.from(request).details();
        assertThat(values).containsEntry("peerIp", "10.0.0.1")
                .containsEntry("forwardedFor", "203.0.113.5, 2001:db8::1")
                .containsEntry("forwardedIpTrust", "UNVERIFIED")
                .containsEntry("origin", "https://vision-certi.web.app");
        assertThat(values.get("userAgent")).hasSize(256);
        assertThat(values.toString()).doesNotContain("never-record", "Authorization");
    }
}
