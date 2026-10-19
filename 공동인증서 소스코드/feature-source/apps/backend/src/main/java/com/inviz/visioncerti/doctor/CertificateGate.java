package com.inviz.visioncerti.doctor;

import java.util.UUID;

/**
 * Certificate enrollment state consulted by {@link DoctorService} under the CERTIFICATE_REQUIRED policy.
 * {@link #hasActiveEnrollment} must not block on the implementation's lock (it is called while DoctorService holds
 * its own monitor).
 */
public interface CertificateGate {

    boolean hasActiveEnrollment(UUID doctorId);

    /**
     * Re-validates the ACTIVE enrollment (internal status, chain, fresh CRL) or throws an {@code ApiException}.
     * Returns the identity of the enrollment that was validated.
     */
    EnrollmentBinding assertSigningAllowed(UUID doctorId);

    /**
     * After a remote signing operation: throws unless {@code binding} is still the doctor's ACTIVE enrollment (not
     * superseded, unregistered or externally revoked meanwhile). Must not call back into DoctorService.
     */
    void confirmBinding(UUID doctorId, EnrollmentBinding binding);

    /** Which enrollment authorised a signature. */
    record EnrollmentBinding(UUID registrationId, String fingerprintSha256, String kind) {
    }
}
