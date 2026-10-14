package com.inviz.visioncerti.enrollment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY. Sends nothing: the code is returned to the caller as {@code devCode}, so a completed
 * challenge proves nothing about who controls the phone number.
 */
@Component
@Profile("dev")
public class MockSmsSender implements SmsSender {

    public static final String MODE = "MOCK_SMS";
    public static final String VERIFICATION_LABEL = "MOCK_VERIFIED";

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public boolean revealsCodeToClient() {
        return true;
    }

    @Override
    public String verificationLabel() {
        return VERIFICATION_LABEL;
    }

    @Override
    public void send(String normalizedPhone, String message) {
        // Intentionally no delivery and no logging.
    }
}
