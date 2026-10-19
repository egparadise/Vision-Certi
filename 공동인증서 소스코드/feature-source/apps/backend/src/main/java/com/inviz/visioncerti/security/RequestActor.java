package com.inviz.visioncerti.security;

/** Authenticated caller bound to exactly one tenant for the duration of a request. */
public record RequestActor(String tenantId, String actorId, ActorRole role) {

    public boolean isDoctor() {
        return role == ActorRole.DOCTOR;
    }
}
