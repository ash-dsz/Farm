package com.farmeraccounts.auth;

/**
 * Delivers one-time passwords. Implementations must never log the code or any provider secret.
 * Select an implementation with {@code app.otp.provider}; add a new SMS vendor by implementing this
 * interface (or configure the generic {@link HttpOtpProvider}) without touching the rest of the app.
 */
public interface OtpProvider {

    /**
     * @param phone   normalised +91 number
     * @param code    the one-time code (only for templating into the outgoing message)
     * @param message full human-readable message containing the code
     */
    void send(String phone, String code, String message);
}
