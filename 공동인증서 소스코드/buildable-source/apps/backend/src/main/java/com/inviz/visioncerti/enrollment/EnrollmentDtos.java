package com.inviz.visioncerti.enrollment;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Request/response bodies for {@code /v1/enrollment}. toString never prints credentials, codes or phones. */
public final class EnrollmentDtos {

    private EnrollmentDtos() {
    }

    public record CreatePhoneChallenge(String phone) {
        @Override
        public String toString() {
            return "CreatePhoneChallenge[phone=<redacted>]";
        }
    }

    /** {@code devCode} is present only when the dev mock sender is active. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhoneChallengeResponse(String challengeId, Instant expiresAt, Instant resendAfter, String mode,
            String devCode) {
        @Override
        public String toString() {
            return "PhoneChallengeResponse[challengeId=" + challengeId + ", expiresAt=" + expiresAt + ", resendAfter="
                    + resendAfter + ", mode=" + mode + ", devCode=<redacted>]";
        }
    }

    public record VerifyPhoneChallenge(String code) {
        @Override
        public String toString() {
            return "VerifyPhoneChallenge[code=<redacted>]";
        }
    }

    public record PhoneVerificationResponse(String verificationToken, Instant expiresAt, String mode) {
        @Override
        public String toString() {
            return "PhoneVerificationResponse[verificationToken=<redacted>, expiresAt=" + expiresAt + ", mode=" + mode
                    + "]";
        }
    }

    public record Consents(Boolean authentication, Boolean signature, Boolean privacy, String version) {
    }

    public record RegistrationRequest(
            String accountType,
            String username,
            String password,
            String name,
            String phone,
            String email,
            String institution,
            String licenseNumber,
            String specialty,
            String verificationToken,
            Consents consents) {
        @Override
        public String toString() {
            return "RegistrationRequest[accountType=" + accountType + ", username=" + username
                    + ", password=<redacted>, phone=<redacted>, verificationToken=<redacted>]";
        }
    }

    public record RegistrationResponse(
            UUID registrationId,
            String username,
            String accountType,
            String status,
            String phoneVerification,
            Instant registeredAt) {
    }
}
