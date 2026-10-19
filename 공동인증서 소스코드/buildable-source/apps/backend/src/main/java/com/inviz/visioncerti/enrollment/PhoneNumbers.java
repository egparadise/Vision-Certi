package com.inviz.visioncerti.enrollment;

import java.util.regex.Pattern;

/** Korean mobile numbers: {@code 010} plus 8 digits; hyphens and spaces are ignored. */
public final class PhoneNumbers {

    private static final Pattern NORMALIZED = Pattern.compile("010[0-9]{8}");
    private static final int MAX_RAW_LENGTH = 32;

    private PhoneNumbers() {
    }

    /** Returns the 11-digit form, or null when the input is not a valid number. */
    public static String normalize(String raw) {
        if (raw == null || raw.length() > MAX_RAW_LENGTH) {
            return null;
        }
        String digits = raw.replace("-", "").replace(" ", "");
        return NORMALIZED.matcher(digits).matches() ? digits : null;
    }
}
