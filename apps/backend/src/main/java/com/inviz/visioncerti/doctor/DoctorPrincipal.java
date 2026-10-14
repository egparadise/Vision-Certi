package com.inviz.visioncerti.doctor;

import java.time.Instant;
import java.util.UUID;

/**
 * A physician authenticated by a bearer session token checked on this request. {@code sessionId} is an opaque
 * server-side handle (not the token); {@code certificateFingerprint} is the full-DER fingerprint proven at a
 * certificate login, null for other identity modes.
 */
public record DoctorPrincipal(UUID doctorId, String tenantId, String identityMode, Instant sessionExpiresAt,
        UUID sessionId, String certificateFingerprint, Instant authenticatedAt) {
}
