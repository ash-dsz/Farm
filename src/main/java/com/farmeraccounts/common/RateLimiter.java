package com.farmeraccounts.common;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory fixed-window limiter, used as a first line of defence per IP. Durable,
 * cross-instance limits (per phone number) are enforced in the database by the OTP service.
 */
@Component
public class RateLimiter {

    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Returns true if the call is allowed; counts it either way. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || now - old.startMillis >= window.toMillis()) {
                return new Window(now, 1);
            }
            return new Window(old.startMillis, old.count + 1);
        });
        if (windows.size() > 50_000) {
            evictOld(now);
        }
        return w.count <= limit;
    }

    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw ApiException.tooManyRequests("Too many attempts. Please wait and try again.");
        }
    }

    public void reset() {
        windows.clear();
    }

    private void evictOld(long now) {
        for (Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().startMillis > Duration.ofHours(24).toMillis()) {
                it.remove();
            }
        }
    }
}
