package com.farmeraccounts.auth;

import com.farmeraccounts.common.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development provider: records that an OTP was requested but does not deliver it, and does not
 * log the code. Use the configured test numbers (app.otp.test-numbers) to log in during development.
 */
@Component
@ConditionalOnProperty(name = "app.otp.provider", havingValue = "console")
public class ConsoleOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleOtpProvider.class);

    @Override
    public void send(String phone, String code, String message) {
        log.warn("Console OTP provider: an OTP was generated for {} but NOT delivered. "
                + "Use a configured test number or configure OTP_PROVIDER=http.", PhoneNumbers.mask(phone));
    }
}
