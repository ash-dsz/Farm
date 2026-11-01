package com.farmeraccounts.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.farmeraccounts.ledger.WageCalculator.Day;
import com.farmeraccounts.ledger.WageCalculator.Rate;
import com.farmeraccounts.ledger.WageCalculator.Summary;
import com.farmeraccounts.ledger.WageCalculator.Txn;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WageCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    private static List<Day> days(int full, int half, int absent) {
        List<Day> days = new ArrayList<>();
        LocalDate d = START;
        for (int i = 0; i < full; i++, d = d.plusDays(1)) {
            days.add(new Day(d, "PRESENT"));
        }
        for (int i = 0; i < half; i++, d = d.plusDays(1)) {
            days.add(new Day(d, "HALF_DAY"));
        }
        for (int i = 0; i < absent; i++, d = d.plusDays(1)) {
            days.add(new Day(d, "ABSENT"));
        }
        return days;
    }

    private static Txn txn(String type, long paise) {
        return new Txn(UUID.randomUUID(), START, type, paise, null);
    }

    @Test
    void specificationExample() {
        // 20 full days x 600 + 2 half days x 600 x 0.5 = 12,600; minus 3,000 advance and 2,000 settlement = 7,600
        Summary s = WageCalculator.summarize(days(20, 2, 3), List.of(), 600_00,
                List.of(txn("ADVANCE", 3_000_00), txn("SETTLEMENT", 2_000_00)));
        assertThat(s.fullDays()).isEqualTo(20);
        assertThat(s.halfDays()).isEqualTo(2);
        assertThat(s.absentDays()).isEqualTo(3);
        assertThat(s.earnedPaise()).isEqualTo(12_600_00);
        assertThat(s.advancesPaise()).isEqualTo(3_000_00);
        assertThat(s.settlementsPaise()).isEqualTo(2_000_00);
        assertThat(s.pendingPaise()).isEqualTo(7_600_00);
    }

    @Test
    void halfDayOfOddWageRoundsOnceAtTheEnd() {
        // Rs 601.01/day: one half day = 300.505 -> 300.51; two half days = exactly 601.01
        assertThat(WageCalculator.summarize(days(0, 1, 0), List.of(), 601_01, List.of()).earnedPaise()).isEqualTo(300_51);
        assertThat(WageCalculator.summarize(days(0, 2, 0), List.of(), 601_01, List.of()).earnedPaise()).isEqualTo(601_01);
    }

    @Test
    void rateChangeAppliesOnlyFromItsEffectiveDate() {
        List<Rate> rates = List.of(
                new Rate(LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T00:00:00Z"), 500_00),
                new Rate(START.plusDays(5), Instant.parse("2026-09-06T00:00:00Z"), 700_00));
        // 10 present days from Sep 1: 5 at 500 and 5 at 700
        Summary s = WageCalculator.summarize(days(10, 0, 0), rates, 999_00, List.of());
        assertThat(s.earnedPaise()).isEqualTo(5 * 500_00 + 5 * 700_00);
    }

    @Test
    void sameDayRateCorrectionUsesLatestCreated() {
        List<Rate> rates = List.of(
                new Rate(START, Instant.parse("2026-09-01T08:00:00Z"), 500_00),
                new Rate(START, Instant.parse("2026-09-01T09:00:00Z"), 550_00));
        assertThat(WageCalculator.rateOn(START, rates, 0)).isEqualTo(550_00);
    }

    @Test
    void daysBeforeFirstRateUseEarliestRate() {
        List<Rate> rates = List.of(new Rate(START.plusDays(30), Instant.now(), 650_00));
        assertThat(WageCalculator.rateOn(START, rates, 1)).isEqualTo(650_00);
        assertThat(WageCalculator.rateOn(START, List.of(), 480_00)).isEqualTo(480_00);
    }

    @Test
    void skippedDaysEarnNothingAndAreNotAbsences() {
        List<Day> days = new ArrayList<>(days(2, 0, 1));
        days.add(new Day(START.plusDays(10), "SKIP"));
        days.add(new Day(START.plusDays(11), "SKIP"));
        Summary s = WageCalculator.summarize(days, List.of(), 600_00, List.of());
        assertThat(s.earnedPaise()).isEqualTo(1_200_00);
        assertThat(s.absentDays()).isEqualTo(1);
        assertThat(s.skippedDays()).isEqualTo(2);
    }

    @Test
    void overpaymentIsShownAsNegativeBalance() {
        Summary s = WageCalculator.summarize(days(2, 0, 0), List.of(), 600_00, List.of(txn("ADVANCE", 2_000_00)));
        assertThat(s.pendingPaise()).isEqualTo(-800_00);
    }

    @Test
    void adjustmentsAreSigned() {
        Summary s = WageCalculator.summarize(days(1, 0, 0), List.of(), 600_00,
                List.of(txn("ADJUSTMENT", 100_00), txn("ADJUSTMENT", -50_00)));
        assertThat(s.adjustmentsPaise()).isEqualTo(50_00);
        assertThat(s.pendingPaise()).isEqualTo(650_00);
    }

    @Test
    void cancelledEntryAndItsReversalAreBothLeftOut() {
        Txn advance = txn("ADVANCE", 1_000_00);
        Txn reversal = new Txn(UUID.randomUUID(), START, "ADJUSTMENT", 1_000_00, advance.id());
        Summary s = WageCalculator.summarize(days(5, 0, 0), List.of(), 600_00, List.of(advance, reversal));
        assertThat(s.advancesPaise()).isZero();
        assertThat(s.adjustmentsPaise()).isZero();
        assertThat(s.pendingPaise()).isEqualTo(3_000_00);
    }
}
