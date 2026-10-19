package com.inviz.visioncerti.security;

public enum ActorRole {
    /** Physician who may approve and sign documents assigned to them. */
    DOCTOR,
    /** Tenant administrator: may register documents and create requests, never approve or sign. */
    ADMIN
}
