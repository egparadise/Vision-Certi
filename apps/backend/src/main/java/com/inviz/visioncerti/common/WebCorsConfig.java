package com.inviz.visioncerti.common;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/** Exact-origin access for a Firebase-hosted browser UI. PACS HMAC calls remain server-to-server. */
@Configuration
public class WebCorsConfig {
    @Bean
    FilterRegistrationBean<CorsFilter> webCorsFilter(
            @Value("${visioncerti.web.allowed-origin:}") String allowedOrigin) {
        FilterRegistrationBean<CorsFilter> registration;
        if (allowedOrigin.isBlank()) {
            registration = new FilterRegistrationBean<>(new CorsFilter(new UrlBasedCorsConfigurationSource()));
            registration.setEnabled(false);
            return registration;
        }
        URI uri;
        try {
            uri = URI.create(allowedOrigin);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid visioncerti.web.allowed-origin", invalid);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getQuery() != null
                || uri.getFragment() != null || !allowedOrigin.equals(uri.toString())) {
            throw new IllegalArgumentException("visioncerti.web.allowed-origin must be an exact HTTPS origin");
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(allowedOrigin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id",
                "X-Tenant-Id", "X-Actor-Id", "X-Actor-Role"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", cors);
        registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
