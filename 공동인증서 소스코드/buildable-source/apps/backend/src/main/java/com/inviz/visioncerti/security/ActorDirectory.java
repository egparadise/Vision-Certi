package com.inviz.visioncerti.security;

import java.util.List;
import java.util.Optional;

/**
 * Synthetic tenants and members for the dev header flow ({@link DevActorDirectory}). Outside that flow
 * ({@link NoSyntheticActorDirectory}) there are none: staff are authenticated by OIDC and mapped explicitly through
 * {@link StaffDirectory}, and physicians sign only as registered doctors with their certificate bearer.
 */
public interface ActorDirectory {

    record Tenant(String id, String displayName) {
    }

    record Member(String actorId, String displayName, ActorRole role) {
    }

    List<Tenant> tenants();

    Optional<Member> member(String tenantId, String actorId);

    default boolean isDoctorInTenant(String tenantId, String actorId) {
        return member(tenantId, actorId).map(m -> m.role() == ActorRole.DOCTOR).orElse(false);
    }
}
