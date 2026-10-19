package com.inviz.visioncerti.enrollment;

/**
 * Delivers one-time phone verification codes. Implementations must not log the phone number or the message.
 * Only {@link MockSmsSender} (dev) exists today; no real SMS provider is integrated, so outside dev
 * {@link NotConfiguredSmsSender} makes personal signup unavailable instead of inventing a carrier.
 */
public interface SmsSender {

    /** False when no provider can deliver codes; signup then refuses with 503 SMS_NOT_CONFIGURED. */
    default boolean available() {
        return true;
    }

    /** Channel label returned to clients, e.g. {@code MOCK_SMS}. */
    String mode();

    /**
     * True only for a development mock that returns the code in the API response instead of delivering it.
     * A real provider must return false.
     */
    boolean revealsCodeToClient();

    /** Phone verification label stored on the account, e.g. {@code MOCK_VERIFIED}. */
    String verificationLabel();

    void send(String normalizedPhone, String message);
}
