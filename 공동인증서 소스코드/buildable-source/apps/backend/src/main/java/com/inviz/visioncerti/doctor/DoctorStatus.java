package com.inviz.visioncerti.doctor;

/** PENDING → IDENTITY_VERIFIED → CONSENTED → ACTIVE; SUSPENDED and REVOKED are terminal in this slice. */
public enum DoctorStatus {
    PENDING,
    IDENTITY_VERIFIED,
    CONSENTED,
    ACTIVE,
    SUSPENDED,
    REVOKED;

    public boolean blocked() {
        return this == SUSPENDED || this == REVOKED;
    }
}
