package com.inviz.visioncerti.enrollment;

import java.time.Instant;
import java.util.UUID;

/**
 * A self-registered account. It carries no role, tenant membership or signing capability, and nothing here
 * can be used to log in yet. Public signup is PERSONAL only; clinician fields are always null (physicians are
 * preregistered by an admin, see {@code DoctorService}).
 */
public record Account(
        UUID registrationId,
        String username,
        AccountType accountType,
        Status status,
        String name,
        String phone,
        String email,
        String institution,
        String licenseNumber,
        String specialty,
        PasswordHasher.PasswordHash passwordHash,
        ConsentRecord consent,
        PhoneVerification phoneVerification,
        Instant registeredAt) {

    /** Public signup creates PERSONAL accounts only; CLINICIAN requests get CLINICIAN_SELF_REGISTRATION_DISABLED. */
    public enum AccountType { PERSONAL }

    public enum Status { REGISTERED }

    /** Immutable record of what was agreed to, and when. */
    public record ConsentRecord(String version, boolean authentication, boolean signature, boolean privacy,
            Instant acceptedAt) {
    }

    /** {@code label} is {@code MOCK_VERIFIED} in dev: the code was shown to the caller, not delivered. */
    public record PhoneVerification(UUID challengeId, String label, Instant verifiedAt) {
    }

    @Override
    public String toString() {
        return "Account[registrationId=" + registrationId + ", username=" + username + ", accountType=" + accountType
                + ", status=" + status + ", <personal data redacted>]";
    }
}
