package com.farmeraccounts.sync;

import static com.farmeraccounts.sync.SyncSupport.clean;
import static com.farmeraccounts.sync.SyncSupport.localDate;
import static com.farmeraccounts.sync.SyncSupport.sqlDate;

import com.farmeraccounts.sync.EntityModels.Attendance;
import com.farmeraccounts.sync.EntityModels.Transaction;
import com.farmeraccounts.sync.EntityModels.WageRate;
import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.OpResult;
import com.farmeraccounts.sync.SyncModels.Operation;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Handlers for attendance, wage rates and worker transactions (the wage ledger). */
public final class LedgerHandlers {

    private LedgerHandlers() {}

    @Component
    public static class WageRateHandler extends AppendOnlyHandler<WageRate> {

        public WageRateHandler(SyncSupport support) {
            super(support, WageRate.class);
        }

        @Override
        public String entityType() {
            return "wageRate";
        }

        @Override
        protected Optional<Stored<WageRate>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM wage_rates WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected WageRate normalize(SyncContext ctx, WageRate r) {
            support.requireReasonableDate(r.effectiveFrom(), "Effective");
            Instant now = Instant.now();
            Instant created = r.createdAt().truncatedTo(ChronoUnit.MILLIS);
            if (created.isAfter(now.plus(1, ChronoUnit.DAYS))) {
                throw OpRejectedException.invalid("Wage rate creation time is in the future");
            }
            return new WageRate(r.id(), r.workerId(), r.dailyWagePaise(), r.effectiveFrom(), created, null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, WageRate r) {
            support.requireWorkerInFarm(ctx, r.workerId(), null);
        }

        @Override
        protected void insert(SyncContext ctx, WageRate r, long version) {
            support.jdbc().sql("""
                    INSERT INTO wage_rates (id, farmer_id, worker_id, daily_wage_paise, effective_from, created_at, server_version)
                    VALUES (:id, :farmer, :worker, :wage, :from, :created, :v)
                    """)
                    .param("id", r.id()).param("farmer", ctx.farmerId()).param("worker", r.workerId())
                    .param("wage", r.dailyWagePaise()).param("from", sqlDate(r.effectiveFrom()))
                    .param("created", Timestamp.from(r.createdAt())).param("v", version)
                    .update();
        }

        @Override
        public List<WageRate> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM wage_rates WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static WageRate map(ResultSet rs) throws SQLException {
            return new WageRate(rs.getObject("id", UUID.class), rs.getObject("worker_id", UUID.class),
                    rs.getLong("daily_wage_paise"), localDate(rs.getDate("effective_from")),
                    rs.getTimestamp("created_at").toInstant().truncatedTo(ChronoUnit.MILLIS),
                    rs.getLong("server_version"));
        }
    }

    @Component
    public static class TransactionHandler extends AppendOnlyHandler<Transaction> {

        public TransactionHandler(SyncSupport support) {
            super(support, Transaction.class);
        }

        @Override
        public String entityType() {
            return "transaction";
        }

        @Override
        protected Optional<Stored<Transaction>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM worker_transactions WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected Transaction normalize(SyncContext ctx, Transaction t) {
            support.requirePastOrToday(t.date(), "Payment");
            boolean adjustment = "ADJUSTMENT".equals(t.type());
            if (adjustment ? t.amountPaise() == 0 : t.amountPaise() <= 0) {
                throw OpRejectedException.invalid("Amount must be more than zero");
            }
            if (t.reversesTransactionId() != null && !adjustment) {
                throw OpRejectedException.invalid("Only an adjustment can cancel another entry");
            }
            if (adjustment && clean(t.note()) == null) {
                throw OpRejectedException.invalid("An adjustment needs a reason");
            }
            return new Transaction(t.id(), t.farmId(), t.workerId(), t.type(), t.amountPaise(), t.date(), clean(t.note()),
                    t.reversesTransactionId(), null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, Transaction t) {
            support.requireWorkerInFarm(ctx, t.workerId(), t.farmId());
            if (t.reversesTransactionId() == null) {
                return;
            }
            Stored<Transaction> original = find(t.reversesTransactionId())
                    .orElseThrow(() -> OpRejectedException.missingParent("The entry being cancelled is not synced yet"));
            if (!original.farmerId().equals(ctx.farmerId())) {
                throw OpRejectedException.forbidden();
            }
            Transaction o = original.data();
            if (!o.workerId().equals(t.workerId())) {
                throw OpRejectedException.invalid("The cancelled entry belongs to another worker");
            }
            if (o.reversesTransactionId() != null) {
                throw OpRejectedException.invalid("A cancellation cannot itself be cancelled");
            }
            // Cancelling must exactly undo the original effect on the balance.
            long expected = "ADJUSTMENT".equals(o.type()) ? -o.amountPaise() : o.amountPaise();
            if (t.amountPaise() != expected) {
                throw OpRejectedException.invalid("A cancellation must exactly reverse the original amount");
            }
            boolean alreadyReversed = support.jdbc()
                    .sql("SELECT count(*) FROM worker_transactions WHERE reverses_transaction_id = :id")
                    .param("id", o.id()).query(Integer.class).single() > 0;
            if (alreadyReversed) {
                throw new OpRejectedException("ALREADY_CANCELLED", "This entry was already cancelled", false);
            }
        }

        @Override
        protected void insert(SyncContext ctx, Transaction t, long version) {
            support.jdbc().sql("""
                    INSERT INTO worker_transactions (id, farmer_id, farm_id, worker_id, type, amount_paise, txn_date,
                                                     note, reverses_transaction_id, server_version)
                    VALUES (:id, :farmer, :farm, :worker, :type, :amount, :date, :note, :reverses, :v)
                    """)
                    .param("id", t.id()).param("farmer", ctx.farmerId()).param("farm", t.farmId())
                    .param("worker", t.workerId()).param("type", t.type()).param("amount", t.amountPaise())
                    .param("date", sqlDate(t.date())).param("note", t.note())
                    .param("reverses", t.reversesTransactionId()).param("v", version)
                    .update();
        }

        @Override
        public List<Transaction> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM worker_transactions WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Transaction map(ResultSet rs) throws SQLException {
            return new Transaction(rs.getObject("id", UUID.class), rs.getObject("farm_id", UUID.class),
                    rs.getObject("worker_id", UUID.class), rs.getString("type"), rs.getLong("amount_paise"),
                    localDate(rs.getDate("txn_date")), rs.getString("note"),
                    rs.getObject("reverses_transaction_id", UUID.class), rs.getLong("server_version"));
        }
    }

    /**
     * Attendance is unique per worker, farm and date. Two devices may create different ids for the
     * same day; the server keeps one row (the first) and tells the other device to adopt its id.
     * A different status for an existing day is applied only on top of the version the client saw.
     */
    @Component
    public static class AttendanceHandler implements EntityHandler {

        private final SyncSupport support;

        public AttendanceHandler(SyncSupport support) {
            this.support = support;
        }

        @Override
        public String entityType() {
            return "attendance";
        }

        @Override
        public String operation() {
            return "upsert";
        }

        private record Stored(UUID farmerId, Attendance data) {}

        private Optional<Stored> findById(UUID id) {
            return support.jdbc().sql("SELECT * FROM attendance WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        private Optional<Attendance> findByKey(Attendance a) {
            return support.jdbc().sql("""
                    SELECT * FROM attendance WHERE worker_id = :w AND farm_id = :f AND work_date = :d FOR UPDATE
                    """)
                    .param("w", a.workerId()).param("f", a.farmId()).param("d", sqlDate(a.date()))
                    .query((rs, n) -> map(rs)).optional();
        }

        @Override
        public OpResult apply(SyncContext ctx, Operation op) {
            Attendance in = support.parse(op.payload(), Attendance.class);
            if (!in.id().equals(op.entityId())) {
                throw OpRejectedException.invalid("Entity id does not match the operation");
            }
            in = in.withVersion(null);
            support.requirePastOrToday(in.date(), "Attendance");

            Optional<Stored> byId = findById(in.id());
            if (byId.isPresent()) {
                if (!byId.get().farmerId().equals(ctx.farmerId())) {
                    throw OpRejectedException.forbidden();
                }
                Attendance cur = byId.get().data();
                if (!cur.workerId().equals(in.workerId()) || !cur.farmId().equals(in.farmId())
                        || !cur.date().equals(in.date())) {
                    throw OpRejectedException.invalid("Worker and date of an attendance record cannot change");
                }
            }
            support.requireWorkerInFarm(ctx, in.workerId(), in.farmId());

            Optional<Attendance> existing = findByKey(in);
            if (existing.isEmpty()) {
                long version = support.nextVersion();
                support.jdbc().sql("""
                        INSERT INTO attendance (id, farmer_id, farm_id, worker_id, work_date, status, server_version)
                        VALUES (:id, :farmer, :farm, :worker, :date, :status, :v)
                        """)
                        .param("id", in.id()).param("farmer", ctx.farmerId()).param("farm", in.farmId())
                        .param("worker", in.workerId()).param("date", sqlDate(in.date())).param("status", in.status())
                        .param("v", version)
                        .update();
                Attendance saved = in.withVersion(version);
                support.audit(ctx, entityType(), saved.id(), "CREATE", null, saved);
                return OpResult.applied(op.opId(), entityType(), saved);
            }

            Attendance cur = existing.get();
            if (cur.status().equals(in.status())) {
                // Same answer already recorded (possibly under another id): adopt the server row.
                return OpResult.applied(op.opId(), entityType(), cur);
            }
            boolean basedOnCurrent = cur.id().equals(in.id()) && op.baseVersion() != null
                    && op.baseVersion().equals(cur.serverVersion());
            if (!basedOnCurrent) {
                return OpResult.conflict(op.opId(), entityType(), cur);
            }
            long version = support.nextVersion();
            support.jdbc().sql("UPDATE attendance SET status = :s, server_version = :v, updated_at = now() WHERE id = :id")
                    .param("s", in.status()).param("v", version).param("id", cur.id()).update();
            Attendance saved = new Attendance(cur.id(), cur.farmId(), cur.workerId(), cur.date(), in.status(), version);
            support.audit(ctx, entityType(), saved.id(), "UPDATE", cur, saved);
            return OpResult.applied(op.opId(), entityType(), saved);
        }

        @Override
        public List<Attendance> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM attendance WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Attendance map(ResultSet rs) throws SQLException {
            return new Attendance(rs.getObject("id", UUID.class), rs.getObject("farm_id", UUID.class),
                    rs.getObject("worker_id", UUID.class), localDate(rs.getDate("work_date")), rs.getString("status"),
                    rs.getLong("server_version"));
        }
    }
}
