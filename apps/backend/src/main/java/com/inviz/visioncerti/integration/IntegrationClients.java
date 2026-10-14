package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * The configured PACS integration client, if any. Not configured (no client id) means every integration route answers
 * {@code 503 INTEGRATION_NOT_CONFIGURED}; a client id with an unusable secret, audience or tenant scope fails startup.
 */
public final class IntegrationClients {

    public static final String DEFAULT_AUDIENCE = "vision-certi:pacs-integration:v1";
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final int MIN_SECRET_BYTES = 32;

    private final IntegrationClient client;

    private IntegrationClients(IntegrationClient client) {
        this.client = client;
    }

    public static IntegrationClients none() {
        return new IntegrationClients(null);
    }

    public static IntegrationClients of(IntegrationClient client) {
        return new IntegrationClients(client);
    }

    /** Builds from configuration values; {@code secretFile} takes precedence over an inline {@code secret}. */
    public static IntegrationClients fromConfig(String clientId, String secretFile, String secret, String audience,
            String tenantIds) {
        if (clientId == null || clientId.isBlank()) {
            return none();
        }
        if (!CLIENT_ID.matcher(clientId).matches()) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_PACS_CLIENT_ID has an invalid format");
        }
        byte[] secretBytes;
        try {
            if (secretFile != null && !secretFile.isBlank()) {
                secretBytes = Files.readString(Path.of(secretFile), StandardCharsets.UTF_8).strip()
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                secretBytes = secret == null ? new byte[0] : secret.strip().getBytes(StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_PACS_CLIENT_SECRET_FILE is not readable");
        }
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("FAIL_CLOSED: the PACS client secret must be at least "
                    + MIN_SECRET_BYTES + " bytes");
        }
        Set<String> tenants = new LinkedHashSet<>();
        if (tenantIds != null) {
            Arrays.stream(tenantIds.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(tenants::add);
        }
        if (tenants.isEmpty()) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_PACS_TENANT_IDS must name at least one tenant");
        }
        String aud = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience.strip();
        IntegrationClient built = new IntegrationClient(clientId, secretBytes, aud, tenants);
        Arrays.fill(secretBytes, (byte) 0);
        return of(built);
    }

    public boolean configured() {
        return client != null;
    }

    public Optional<IntegrationClient> find(String clientId) {
        return client != null && client.clientId().equals(clientId) ? Optional.of(client) : Optional.empty();
    }

    public IntegrationClient require() {
        if (client == null) {
            throw notConfigured();
        }
        return client;
    }

    static ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_NOT_CONFIGURED",
                "PACS 연동이 설정되지 않았습니다 (VISIONCERTI_PACS_CLIENT_ID).");
    }
}
