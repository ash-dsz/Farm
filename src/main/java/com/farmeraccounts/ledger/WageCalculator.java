package com.farmeraccounts.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure wage arithmetic, shared in spirit with the mobile app's wage.ts (keep the two in step).
 *
 * <pre>
 * earned  = sum over days of (Present = 1, Half day = 0.5, Absent = Skip = 0) x rate applicable on that day
 * pending = earned - advances - settlements + adjustments
 * </pre>
 *
 * The applicable rate for a day is the wage rate with the latest {@code effectiveFrom <= day}
 * (ties: the most recently created). Days before the first rate use the earliest rate; a worker
 * without any rate history uses {@code fallbackDailyWagePaise}. All amounts are integer paise;
 * half days are summed in half-paise units and rounded once (half up) at the end.
 *
 * Entries cancelled by a reversal, and the reversal itself, are left out of the totals (their
 * effects cancel exactly), so reports show what actually stands.
 */
public final class WageCalculator {

    private WageCalculator() {}

    public record Day(LocalDate date, String status) {}

    public record Rate(LocalDate effectiveFrom, Instant createdAt, long dailyWagePaise) {}

    public record Txn(UUID id, LocalDate date, String type, long amountPaise, UUID reversesTransactionId) {}

    /** skippedDays: days the worker was not expected (SKIP); neither worked nor absent. */
    public record Summary(int fullDays, int halfDays, int absentDays, long earnedPaise, long advancesPaise,
                          long settlementsPaise, long adjustmentsPaise, long pendingPaise, int skippedDays) {}

    public static long rateOn(LocalDate day, List<Rate> rates, long fallbackDailyWagePaise) {
        if (rates.isEmpty()) {
            return fallbackDailyWagePaise;
        }
        List<Rate> sorted = rates.stream()
                .sorted(Comparator.comparing(Rate::effectiveFrom).thenComparing(Rate::createdAt))
                .toList();
        Rate chosen = sorted.get(0);
        for (Rate r : sorted) {
            if (!r.effectiveFrom().isAfter(day)) {
                chosen = r;
            }
        }
        return chosen.dailyWagePaise();
    }

    public static Summary summarize(List<Day> days, List<Rate> rates, long fallbackDailyWagePaise, List<Txn> txns) {
        int full = 0;
        int half = 0;
        int absent = 0;
        int skipped = 0;
        long halfUnits = 0; // in half-paise
        for (Day d : days) {
            switch (d.status()) {
                case "PRESENT" -> {
                    full++;
                    halfUnits += 2 * rateOn(d.date(), rates, fallbackDailyWagePaise);
                }
                case "HALF_DAY" -> {
                    half++;
                    halfUnits += rateOn(d.date(), rates, fallbackDailyWagePaise);
                }
                case "SKIP" -> skipped++;
                default -> absent++;
            }
        }
        long earned = (halfUnits + 1) / 2;

        Set<UUID> cancelled = new HashSet<>();
        for (Txn t : txns) {
            if (t.reversesTransactionId() != null) {
                cancelled.add(t.reversesTransactionId());
            }
        }
        long advances = 0;
        long settlements = 0;
        long adjustments = 0;
        for (Txn t : txns) {
            if (t.reversesTransactionId() != null || cancelled.contains(t.id())) {
                continue;
            }
            switch (t.type()) {
                case "ADVANCE" -> advances += t.amountPaise();
                case "SETTLEMENT" -> settlements += t.amountPaise();
                case "ADJUSTMENT" -> adjustments += t.amountPaise();
                default -> throw new IllegalArgumentException("Unknown transaction type " + t.type());
            }
        }
        long pending = earned - advances - settlements + adjustments;
        return new Summary(full, half, absent, earned, advances, settlements, adjustments, pending, skipped);
    }
}
