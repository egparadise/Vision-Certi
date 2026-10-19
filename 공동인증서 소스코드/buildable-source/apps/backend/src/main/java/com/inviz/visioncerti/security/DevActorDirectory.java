package com.inviz.visioncerti.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic synthetic tenants and actors for the {@code dev} profile only. No real people,
 * licenses or hospitals are represented.
 */
@Component
@Profile("dev")
@Conditional(SyntheticActorsCondition.class)
public class DevActorDirectory implements ActorDirectory {

    public static final String TENANT_A = "demo-hospital-a";
    public static final String TENANT_B = "demo-hospital-b";
    public static final String DOCTOR = "doctor-demo";
    public static final String ADMIN = "admin-demo";

    private static final List<Tenant> TENANTS = List.of(
            new Tenant(TENANT_A, "Demo Hospital A (synthetic)"),
            new Tenant(TENANT_B, "Demo Hospital B (synthetic)"));

    private static final Map<String, Member> MEMBERS = Map.of(
            DOCTOR, new Member(DOCTOR, "Demo Doctor (synthetic)", ActorRole.DOCTOR),
            ADMIN, new Member(ADMIN, "Demo Admin (synthetic)", ActorRole.ADMIN));

    @Override
    public List<Tenant> tenants() {
        return TENANTS;
    }

    @Override
    public Optional<Member> member(String tenantId, String actorId) {
        // Both synthetic actors belong to both synthetic tenants; data is still isolated per tenant.
        boolean knownTenant = TENANTS.stream().anyMatch(t -> t.id().equals(tenantId));
        return knownTenant ? Optional.ofNullable(MEMBERS.get(actorId)) : Optional.empty();
    }
}
