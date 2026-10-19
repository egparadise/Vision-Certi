package com.inviz.visioncerti.doctor;

import java.time.Duration;

/** Lifetimes and limits for invitations, challenges and sessions. */
public record DoctorPolicy(
        Duration invitationTtl,
        Duration challengeTtl,
        Duration sessionTtl,
        int maxIdentityFailuresPerInvitation,
        int maxChallengesPerInvitation,
        int sourceFailureHourlyLimit,
        int sourceChallengeHourlyLimit,
        int maxTrackedEntries,
        int maxDoctors) {

    public static DoctorPolicy defaults() {
        return new DoctorPolicy(Duration.ofHours(72), Duration.ofMinutes(10), Duration.ofHours(1), 5, 10, 10, 30,
                10_000, 10_000);
    }
}
