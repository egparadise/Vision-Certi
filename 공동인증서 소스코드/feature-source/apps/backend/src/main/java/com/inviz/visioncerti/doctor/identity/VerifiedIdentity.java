package com.inviz.visioncerti.doctor.identity;

/**
 * Identity asserted by a verified ID token (or the dev MOCK_IDENTITY route). {@code email} is lower-case and was
 * reported verified by the issuer. Nothing here says anything about real name or medical license.
 */
public record VerifiedIdentity(String mode, String issuer, String subject, String email) {

    public static final String GOOGLE = "GOOGLE";
    public static final String MOCK_IDENTITY = "MOCK_IDENTITY";
    public static final String MOCK_ISSUER = "urn:vision-certi:mock-identity";

    public String bindingKey() {
        return issuer + "|" + subject;
    }

    @Override
    public String toString() {
        return "VerifiedIdentity[mode=" + mode + ", issuer=" + issuer + ", <redacted>]";
    }
}
