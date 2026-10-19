package com.inviz.visioncerti.integration;

import java.util.Set;

/**
 * A configured service client (the SV70 PACS server). The HMAC secret never leaves this object and is redacted in
 * {@link #toString()}. {@code actorId} is the audit/ownership identity of everything the client creates.
 */
public final class IntegrationClient {

    private final String clientId;
    private final byte[] secret;
    private final String audience;
    private final Set<String> tenantIds;

    public IntegrationClient(String clientId, byte[] secret, String audience, Set<String> tenantIds) {
        this.clientId = clientId;
        this.secret = secret.clone();
        this.audience = audience;
        this.tenantIds = Set.copyOf(tenantIds);
    }

    public String clientId() {
        return clientId;
    }

    byte[] secret() {
        return secret.clone();
    }

    public String audience() {
        return audience;
    }

    public Set<String> tenantIds() {
        return tenantIds;
    }

    public boolean allowsTenant(String tenantId) {
        return tenantId != null && tenantIds.contains(tenantId);
    }

    public String actorId() {
        return "integration:" + clientId;
    }

    @Override
    public String toString() {
        return "IntegrationClient[" + clientId + ", tenants=" + tenantIds + ", secret=<redacted>]";
    }
}
