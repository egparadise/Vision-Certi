package com.inviz.visioncerti.security;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Staff authentication wiring. {@code visioncerti.staff-auth.mode=OIDC} (required in prod) installs
 * {@link StaffAuthFilter} with a remote-JWKS {@link StaffTokenVerifier}; the issuer, audience and JWKS URI come only
 * from server configuration. The staff directory always exists (it is durable) and is optionally bootstrapped from a
 * protected local file at startup.
 */
@Configuration
public class StaffAuthConfig {

    @Bean
    StaffDirectory staffDirectory(Store store, AuditService audit, Clock clock,
            @Value("${visioncerti.staff-auth.oidc.issuer:}") String issuer,
            @Value("${visioncerti.staff-auth.bootstrap-file:}") String bootstrapFile) {
        StaffDirectory directory = new StaffDirectory(store, audit, clock, issuer.isBlank() ? null : issuer.trim());
        if (!bootstrapFile.isBlank()) {
            if (issuer.isBlank()) {
                throw new IllegalStateException(
                        "FAIL_CLOSED: VISIONCERTI_STAFF_BOOTSTRAP_FILE requires VISIONCERTI_STAFF_OIDC_ISSUER");
            }
            directory.bootstrap(Path.of(bootstrapFile.trim()));
        }
        return directory;
    }

    @Bean
    @ConditionalOnProperty(name = SyntheticActorsCondition.MODE_PROPERTY, havingValue = SyntheticActorsCondition.OIDC)
    StaffTokenVerifier staffTokenVerifier(Clock clock,
            @Value("${visioncerti.staff-auth.oidc.issuer:}") String issuer,
            @Value("${visioncerti.staff-auth.oidc.audience:}") String audience,
            @Value("${visioncerti.staff-auth.oidc.jwks-uri:}") String jwksUri) {
        String problem = OidcSettings.problem(issuer, audience, jwksUri, false);
        if (problem != null) {
            throw new IllegalStateException("FAIL_CLOSED: " + problem);
        }
        return StaffTokenVerifier.remote(issuer.trim(), audience.trim(), jwksUri.trim(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = SyntheticActorsCondition.MODE_PROPERTY, havingValue = SyntheticActorsCondition.OIDC)
    StaffAuthFilter staffAuthFilter(StaffTokenVerifier verifier, StaffDirectory directory,
            @Value("${visioncerti.staff-auth.initial-google-admin-email:}") String initialAdminEmail,
            @Value("${visioncerti.staff-auth.initial-google-admin-tenant-id:}") String initialAdminTenantId) {
        return new StaffAuthFilter(verifier, directory, initialAdminEmail, initialAdminTenantId);
    }

    /** Validation of the server-side OIDC settings (names only in messages, never values). */
    public static final class OidcSettings {

        private OidcSettings() {
        }

        public static String problem(String issuer, String audience, String jwksUri, boolean requireHttps) {
            if (issuer == null || issuer.isBlank()) {
                return "VISIONCERTI_STAFF_OIDC_ISSUER is not set";
            }
            if (audience == null || audience.isBlank()) {
                return "VISIONCERTI_STAFF_OIDC_AUDIENCE is not set";
            }
            if (jwksUri == null || jwksUri.isBlank()) {
                return "VISIONCERTI_STAFF_OIDC_JWKS_URI is not set";
            }
            if (requireHttps && (!issuer.trim().startsWith("https://") || !jwksUri.trim().startsWith("https://"))) {
                return "VISIONCERTI_STAFF_OIDC_ISSUER and _JWKS_URI must use https";
            }
            return null;
        }
    }
}
