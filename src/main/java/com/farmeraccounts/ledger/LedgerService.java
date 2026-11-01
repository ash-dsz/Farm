package com.farmeraccounts.ledger;

import static com.farmeraccounts.sync.SyncSupport.localDate;
import static com.farmeraccounts.sync.SyncSupport.sqlDate;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.ledger.WageCalculator.Day;
import com.farmeraccounts.ledger.WageCalculator.Rate;
import com.farmeraccounts.ledger.WageCalculator.Summary;
import com.farmeraccounts.ledger.WageCalculator.Txn;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Computes worker balances from the underlying attendance and transaction records. */
@Service
public class LedgerService {

    public record WorkerLedger(UUID workerId, String workerName, String month, Summary period, Summary allTime) {}

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param farmerId owner check: only this farmer's worker can be read
     * @param month    optional YYYY-MM restricting the period summary
     */
    public WorkerLedger ledger(UUID farmerId, UUID workerId, String month) {
        record W(String name, long wage) {}
        W worker = jdbc.sql("SELECT name, daily_wage_paise FROM workers WHERE id = :id AND farmer_id = :f")
                .param("id", workerId).param("f", farmerId)
                .query((rs, n) -> new W(rs.getString("name"), rs.getLong("daily_wage_paise")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Worker not found"));
        Summary all = summarize(workerId, worker.wage(), null, null);
        Summary period = all;
        if (month != null) {
            YearMonth ym;
            try {
                ym = YearMonth.parse(month);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("INVALID_MONTH", "Month must look like 2026-10");
            }
            period = summarize(workerId, worker.wage(), ym.atDay(1), ym.atEndOfMonth());
        }
        return new WorkerLedger(workerId, worker.name(), month, period, all);
    }

    public Summary summarize(UUID workerId, long fallbackWage, LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.of(1900, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(9999, 12, 31) : to;
        List<Day> days = jdbc.sql("""
                SELECT work_date, status FROM attendance WHERE worker_id = :w AND work_date BETWEEN :s AND :e
                """)
                .param("w", workerId).param("s", sqlDate(start)).param("e", sqlDate(end))
                .query((rs, n) -> new Day(localDate(rs.getDate("work_date")), rs.getString("status"))).list();
        List<Rate> rates = rates(workerId);
        // All transactions are loaded so that cancellations are recognised across periods.
        List<Txn> txns = jdbc.sql("""
                SELECT id, txn_date, type, amount_paise, reverses_transaction_id FROM worker_transactions WHERE worker_id = :w
                """)
                .param("w", workerId)
                .query((rs, n) -> new Txn(rs.getObject("id", UUID.class), localDate(rs.getDate("txn_date")),
                        rs.getString("type"), rs.getLong("amount_paise"), rs.getObject("reverses_transaction_id", UUID.class)))
                .list();
        java.util.Set<UUID> cancelledAnywhere = new java.util.HashSet<>();
        txns.forEach(t -> {
            if (t.reversesTransactionId() != null) {
                cancelledAnywhere.add(t.reversesTransactionId());
            }
        });
        List<Txn> inPeriod = txns.stream()
                .filter(t -> !t.date().isBefore(start) && !t.date().isAfter(end))
                // Leave out originals cancelled at any time, and the cancelling entries themselves.
                .filter(t -> t.reversesTransactionId() == null && !cancelledAnywhere.contains(t.id()))
                .toList();
        return WageCalculator.summarize(days, rates, fallbackWage, inPeriod);
    }

    public List<Rate> rates(UUID workerId) {
        return jdbc.sql("SELECT effective_from, created_at, daily_wage_paise FROM wage_rates WHERE worker_id = :w")
                .param("w", workerId)
                .query((rs, n) -> new Rate(localDate(rs.getDate("effective_from")), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("daily_wage_paise")))
                .list();
    }
}
