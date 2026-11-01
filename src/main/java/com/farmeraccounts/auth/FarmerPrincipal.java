package com.farmeraccounts.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * The authenticated farmer, resolved from the JWT for controller methods that declare it.
 *
 * @param otpVerifiedAt set only on tokens issued directly after OTP verification
 */
public record FarmerPrincipal(UUID userId, String deviceId, Instant otpVerifiedAt) {}
