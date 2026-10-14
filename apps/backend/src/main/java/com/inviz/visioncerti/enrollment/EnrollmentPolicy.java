package com.inviz.visioncerti.enrollment;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limits for phone challenges, verification tokens and the in-memory stores. */
public record EnrollmentPolicy(
        Duration challengeTtl,
        Duration resendCooldown,
        int maxVerifyAttempts,
        int phoneHourlyLimit,
        int sourceHourlyLimit,
        Duration verificationTokenTtl,
        int maxTrackedEntries,
        int maxAccounts) {

    public static EnrollmentPolicy defaults() {
        return new EnrollmentPolicy(Duration.ofMinutes(3), Duration.ofSeconds(60), 5, 5, 30, Duration.ofMinutes(10),
                10_000, 10_000);
    }

    @Configuration
    static class Config {

        @Bean
        EnrollmentPolicy enrollmentPolicy(
                @Value("${visioncerti.enrollment.phone-hourly-limit:5}") int phoneHourlyLimit,
                @Value("${visioncerti.enrollment.source-hourly-limit:30}") int sourceHourlyLimit,
                @Value("${visioncerti.enrollment.max-tracked-entries:10000}") int maxTrackedEntries,
                @Value("${visioncerti.enrollment.max-accounts:10000}") int maxAccounts) {
            EnrollmentPolicy d = defaults();
            return new EnrollmentPolicy(d.challengeTtl(), d.resendCooldown(), d.maxVerifyAttempts(), phoneHourlyLimit,
                    sourceHourlyLimit, d.verificationTokenTtl(), maxTrackedEntries, maxAccounts);
        }
    }
}
