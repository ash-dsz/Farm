package com.farmeraccounts.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward to exercise expiry, lockouts and rate-limit windows. */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void reset() {
        now = Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock outer = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId z) {
                return outer.withZone(z);
            }

            @Override
            public Instant instant() {
                return outer.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        return now;
    }
}
