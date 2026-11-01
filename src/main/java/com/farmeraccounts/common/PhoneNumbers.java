package com.farmeraccounts.common;

import java.util.regex.Pattern;

/** Indian mobile numbers, normalised to +91XXXXXXXXXX. */
public final class PhoneNumbers {

    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    private PhoneNumbers() {}

    /** Returns the normalised number or throws INVALID_PHONE. */
    public static String normalizeIndian(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("INVALID_PHONE", "Enter a valid 10 digit mobile number");
        }
        String digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0091")) {
            digits = digits.substring(4);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (!INDIAN_MOBILE.matcher(digits).matches()) {
            throw ApiException.badRequest("INVALID_PHONE", "Enter a valid 10 digit mobile number");
        }
        return "+91" + digits;
    }

    /** Same as {@link #normalizeIndian} but returns null instead of throwing (for optional fields). */
    public static String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return normalizeIndian(raw);
    }

    /** For logs: +91******1234. */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return phone.substring(0, 3) + "******" + phone.substring(phone.length() - 4);
    }
}
