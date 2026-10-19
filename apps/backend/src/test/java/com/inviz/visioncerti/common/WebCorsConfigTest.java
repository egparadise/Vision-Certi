package com.inviz.visioncerti.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebCorsConfigTest {
    private final WebCorsConfig config = new WebCorsConfig();

    @Test
    void allowsOnlyTheConfiguredFirebaseOriginBeforeAuthentication() throws Exception {
        var filter = config.webCorsFilter("https://vision-certi.web.app").getFilter();
        var allowed = preflight("https://vision-certi.web.app");
        var response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(allowed, response, (request, result) -> continued.set(true));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://vision-certi.web.app");
        assertThat(continued.get()).isFalse();

        var rejected = new MockHttpServletResponse();
        filter.doFilter(preflight("https://untrusted.example"), rejected, (request, result) -> continued.set(true));
        assertThat(rejected.getStatus()).isEqualTo(403);
        assertThat(rejected.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void corsIsDisabledWithoutAnOriginAndRejectsUnsafeConfiguration() {
        assertThat(config.webCorsFilter("").isEnabled()).isFalse();
        assertThatThrownBy(() -> config.webCorsFilter("http://vision-certi.web.app"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.webCorsFilter("https://vision-certi.web.app/path"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest preflight(String origin) {
        var request = new MockHttpServletRequest("OPTIONS", "/v1/health");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "GET");
        return request;
    }
}
