package com.farmeraccounts.common;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientInfo {

    public static final String DEVICE_HEADER = "X-Device-Id";
    public static final String VERSION_HEADER = "X-App-Version";

    private ClientInfo() {}

    /** Remote address; X-Forwarded-For is already applied by forward-headers-strategy=framework. */
    public static String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public static String deviceId(HttpServletRequest request) {
        return sanitizeDeviceId(request.getHeader(DEVICE_HEADER));
    }

    public static String sanitizeDeviceId(String id) {
        if (id == null || id.isBlank() || id.length() > 64 || !id.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return id;
    }
}
