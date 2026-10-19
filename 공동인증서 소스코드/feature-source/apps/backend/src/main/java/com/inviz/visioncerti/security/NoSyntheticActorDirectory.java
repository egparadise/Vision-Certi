package com.inviz.visioncerti.security;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** No synthetic tenants or actors: used in prod and whenever staff authentication is OIDC. */
@Component
@Conditional(SyntheticActorsCondition.Disabled.class)
public class NoSyntheticActorDirectory implements ActorDirectory {

    @Override
    public List<Tenant> tenants() {
        return List.of();
    }

    @Override
    public Optional<Member> member(String tenantId, String actorId) {
        return Optional.empty();
    }
}
