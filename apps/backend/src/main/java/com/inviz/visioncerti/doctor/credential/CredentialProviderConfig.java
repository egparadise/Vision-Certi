package com.inviz.visioncerti.doctor.credential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Selects the single physician credential backend from {@code VISIONCERTI_SIGNING_BACKEND}. Incomplete OpenBao
 * settings yield an unavailable provider (activation fails closed); there is no fallback to a simulated backend.
 */
@Configuration
public class CredentialProviderConfig {

    @Bean
    DoctorCredentialProvider doctorCredentialProvider(Environment environment, Clock clock,
            @Value("${visioncerti.doctor.signing-backend:NOT_CONFIGURED}") String backend,
            @Value("${visioncerti.openbao.addr:}") String addr,
            @Value("${visioncerti.openbao.namespace:}") String namespace,
            @Value("${visioncerti.openbao.provisioning-token:}") String provisioningTokenValue,
            @Value("${visioncerti.openbao.signing-token:}") String signingTokenValue,
            @Value("${visioncerti.openbao.provisioning-token-file:}") String provisioningTokenFile,
            @Value("${visioncerti.openbao.signing-token-file:}") String signingTokenFile,
            @Value("${visioncerti.openbao.transit-mount:transit}") String transitMount,
            @Value("${visioncerti.openbao.pki-mount:pki}") String pkiMount,
            @Value("${visioncerti.openbao.pki-role:vision-certi-doctor}") String pkiRole,
            @Value("${visioncerti.openbao.key-prefix:vc-doctor}") String keyPrefix,
            @Value("${visioncerti.openbao.cert-ttl:8760h}") String certTtl,
            @Value("${visioncerti.openbao.ca-cert-file:}") String caCertFile,
            @Value("${visioncerti.openbao.timeout:PT10S}") Duration timeout) {
        String selected = backend == null ? "" : backend.trim().toUpperCase(Locale.ROOT);
        boolean prod = environment.acceptsProfiles(Profiles.of("prod", "production"));
        if (prod && !DoctorCredentialProvider.OPENBAO_TRANSIT.equals(selected)) {
            throw new IllegalStateException("FAIL_CLOSED: prod requires VISIONCERTI_SIGNING_BACKEND=OPENBAO_TRANSIT");
        }
        // Token files are preferred (and required in prod); a file wins over an environment value.
        String provisioningToken = tokenFrom(provisioningTokenFile, provisioningTokenValue, prod,
                "VISIONCERTI_OPENBAO_PROVISIONING_TOKEN_FILE");
        String signingToken = tokenFrom(signingTokenFile, signingTokenValue, prod,
                "VISIONCERTI_OPENBAO_SIGNING_TOKEN_FILE");
        switch (selected) {
            case "", DoctorCredentialProvider.NOT_CONFIGURED:
                return new NotConfiguredCredentialProvider(DoctorCredentialProvider.NOT_CONFIGURED,
                        "VISIONCERTI_SIGNING_BACKEND is not set");
            case DoctorCredentialProvider.DEV_SIMULATED:
                if (!environment.acceptsProfiles(Profiles.of("dev"))) {
                    throw new IllegalStateException("FAIL_CLOSED: DEV_SIMULATED signing is only allowed in the dev profile");
                }
                return new DevSimulatedCredentialProvider(clock);
            case DoctorCredentialProvider.OPENBAO_TRANSIT:
                OpenBaoProperties properties = new OpenBaoProperties(addr, namespace, provisioningToken, signingToken,
                        transitMount, pkiMount, pkiRole, keyPrefix, certTtl, caCertFile, timeout);
                String problem = properties.problem();
                if (problem == null && prod && !addr.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
                    problem = "VISIONCERTI_OPENBAO_ADDR must use https in prod";
                }
                if (problem == null && prod && (caCertFile == null || caCertFile.isBlank())) {
                    problem = "VISIONCERTI_OPENBAO_CA_CERT_FILE is required in prod";
                }
                if (problem != null) {
                    if (prod) {
                        throw new IllegalStateException("FAIL_CLOSED: " + problem);
                    }
                    return new NotConfiguredCredentialProvider(DoctorCredentialProvider.OPENBAO_TRANSIT, problem);
                }
                return new OpenBaoCredentialProvider(properties, clock,
                        prod ? OpenBaoCredentialProvider.PROD_SIGNATURE_MODE : OpenBaoCredentialProvider.SIGNATURE_MODE);
            default:
                return new NotConfiguredCredentialProvider(DoctorCredentialProvider.NOT_CONFIGURED,
                        "VISIONCERTI_SIGNING_BACKEND has an unknown value");
        }
    }

    /** Reads a token file (whitespace trimmed, never logged); outside prod an environment value is accepted. */
    static String tokenFrom(String file, String value, boolean prod, String fileVariable) {
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("FAIL_CLOSED: " + fileVariable + " is not readable");
            }
        }
        if (prod && value != null && !value.isBlank()) {
            throw new IllegalStateException("FAIL_CLOSED: prod requires " + fileVariable
                    + " instead of a token in the environment");
        }
        return value;
    }
}
