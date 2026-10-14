package com.inviz.visioncerti;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Vision-Certi backend, development slice.
 *
 * <p>Only the explicitly enabled {@code dev} profile is runnable. Every other profile fails closed at
 * startup (see {@link com.inviz.visioncerti.security.FailClosedProfileGuard}) because real
 * authentication (OIDC/MFA), HSM signing, TSA and PostgreSQL persistence are not implemented yet.
 */
@SpringBootApplication
public class VisionCertiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisionCertiApplication.class, args);
    }
}
