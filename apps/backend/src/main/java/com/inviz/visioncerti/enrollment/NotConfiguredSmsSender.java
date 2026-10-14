package com.inviz.visioncerti.enrollment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Outside dev: no SMS carrier is integrated, so phone verification (and personal signup) is unavailable. */
@Component
@Profile("!dev")
public class NotConfiguredSmsSender implements SmsSender {

    public static final String MODE = "NOT_CONFIGURED";

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public boolean revealsCodeToClient() {
        return false;
    }

    @Override
    public String verificationLabel() {
        return MODE;
    }

    @Override
    public void send(String normalizedPhone, String message) {
        throw new IllegalStateException("SMS provider not configured");
    }
}
