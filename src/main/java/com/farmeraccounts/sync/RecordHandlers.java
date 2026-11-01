package com.farmeraccounts.sync;

import static com.farmeraccounts.sync.SyncSupport.clean;
import static com.farmeraccounts.sync.SyncSupport.localDate;
import static com.farmeraccounts.sync.SyncSupport.sqlDate;

import com.farmeraccounts.sync.EntityModels.Expense;
import com.farmeraccounts.sync.EntityModels.Sale;
import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Handlers for farm expenses and produce sales. Edits are versioned and audited. */
public final class RecordHandlers {

    private RecordHandlers() {}

    @Component
    public static class ExpenseHandler extends UpsertHandler<Expense> {

        public ExpenseHandler(SyncSupport support) {
            super(support, Expense.class);
        }

        @Override
        public String entityType() {
            return "expense";
        }

        @Override
        protected Optional<Stored<Expense>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM expenses WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected Expense normalize(SyncContext ctx, Expense e) {
            support.requirePastOrToday(e.date(), "Expense");
            return new Expense(e.id(), e.farmId(), e.fieldId(), e.category(), clean(e.description()), e.amountPaise(),
                    e.date(), clean(e.notes()), e.deleted(), null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, Expense e) {
            support.requireFarm(ctx, e.farmId());
            if (e.fieldId() != null) {
                support.requireFieldInFarm(ctx, e.fieldId(), e.farmId());
            }
        }

        @Override
        protected void insert(SyncContext ctx, Expense e, long version) {
            support.jdbc().sql("""
                    INSERT INTO expenses (id, farmer_id, farm_id, field_id, category, description, amount_paise,
                                          expense_date, notes, deleted_at, server_version)
                    VALUES (:id, :farmer, :farm, :field, :category, :description, :amount, :date, :notes,
                            CASE WHEN :deleted THEN now() ELSE NULL END, :v)
                    """)
                    .param("id", e.id()).param("farmer", ctx.farmerId()).param("farm", e.farmId())
                    .param("field", e.fieldId()).param("category", e.category()).param("description", e.description())
                    .param("amount", e.amountPaise()).param("date", sqlDate(e.date())).param("notes", e.notes())
                    .param("deleted", e.deleted()).param("v", version)
                    .update();
        }

        @Override
        protected void update(SyncContext ctx, Expense e, long version) {
            support.jdbc().sql("""
                    UPDATE expenses SET field_id = :field, category = :category, description = :description,
                        amount_paise = :amount, expense_date = :date, notes = :notes,
                        deleted_at = CASE WHEN :deleted THEN COALESCE(deleted_at, now()) ELSE NULL END,
                        server_version = :v, updated_at = now()
                    WHERE id = :id
                    """)
                    .param("id", e.id()).param("field", e.fieldId()).param("category", e.category())
                    .param("description", e.description()).param("amount", e.amountPaise())
                    .param("date", sqlDate(e.date())).param("notes", e.notes()).param("deleted", e.deleted())
                    .param("v", version)
                    .update();
        }

        @Override
        public List<Expense> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM expenses WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Expense map(ResultSet rs) throws SQLException {
            return new Expense(rs.getObject("id", UUID.class), rs.getObject("farm_id", UUID.class),
                    rs.getObject("field_id", UUID.class), rs.getString("category"), rs.getString("description"),
                    rs.getLong("amount_paise"), localDate(rs.getDate("expense_date")), rs.getString("notes"),
                    rs.getTimestamp("deleted_at") != null, rs.getLong("server_version"));
        }
    }

    @Component
    public static class SaleHandler extends UpsertHandler<Sale> {

        public SaleHandler(SyncSupport support) {
            super(support, Sale.class);
        }

        @Override
        public String entityType() {
            return "sale";
        }

        @Override
        protected Optional<Stored<Sale>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM sales WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected Sale normalize(SyncContext ctx, Sale s) {
            support.requirePastOrToday(s.date(), "Sale");
            BigDecimal quantity = clean(s.quantity());
            if (quantity.scale() > 3) {
                throw OpRejectedException.invalid("Quantity can have at most 3 decimals");
            }
            if (s.receivedPaise() > s.totalPaise()) {
                throw OpRejectedException.invalid("Received amount cannot be more than the total");
            }
            // When a rate is given, the total must match quantity x rate (rounded to the paisa).
            if (s.ratePaise() > 0) {
                long expected = quantity.multiply(BigDecimal.valueOf(s.ratePaise()))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                if (expected != s.totalPaise()) {
                    throw OpRejectedException.invalid("Total does not match quantity x rate");
                }
            }
            return new Sale(s.id(), s.farmId(), s.crop(), quantity, s.unit(), s.ratePaise(), s.totalPaise(),
                    s.receivedPaise(), clean(s.buyerName()), s.date(), clean(s.notes()), s.deleted(), null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, Sale s) {
            support.requireFarm(ctx, s.farmId());
        }

        @Override
        protected void insert(SyncContext ctx, Sale s, long version) {
            support.jdbc().sql("""
                    INSERT INTO sales (id, farmer_id, farm_id, crop, quantity, unit, rate_paise, total_paise,
                                       received_paise, buyer_name, sale_date, notes, deleted_at, server_version)
                    VALUES (:id, :farmer, :farm, :crop, :qty, :unit, :rate, :total, :received, :buyer, :date, :notes,
                            CASE WHEN :deleted THEN now() ELSE NULL END, :v)
                    """)
                    .param("id", s.id()).param("farmer", ctx.farmerId()).param("farm", s.farmId())
                    .param("crop", s.crop()).param("qty", s.quantity()).param("unit", s.unit())
                    .param("rate", s.ratePaise()).param("total", s.totalPaise()).param("received", s.receivedPaise())
                    .param("buyer", s.buyerName()).param("date", sqlDate(s.date())).param("notes", s.notes())
                    .param("deleted", s.deleted()).param("v", version)
                    .update();
        }

        @Override
        protected void update(SyncContext ctx, Sale s, long version) {
            support.jdbc().sql("""
                    UPDATE sales SET crop = :crop, quantity = :qty, unit = :unit, rate_paise = :rate,
                        total_paise = :total, received_paise = :received, buyer_name = :buyer, sale_date = :date,
                        notes = :notes, deleted_at = CASE WHEN :deleted THEN COALESCE(deleted_at, now()) ELSE NULL END,
                        server_version = :v, updated_at = now()
                    WHERE id = :id
                    """)
                    .param("id", s.id()).param("crop", s.crop()).param("qty", s.quantity()).param("unit", s.unit())
                    .param("rate", s.ratePaise()).param("total", s.totalPaise()).param("received", s.receivedPaise())
                    .param("buyer", s.buyerName()).param("date", sqlDate(s.date())).param("notes", s.notes())
                    .param("deleted", s.deleted()).param("v", version)
                    .update();
        }

        @Override
        public List<Sale> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM sales WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Sale map(ResultSet rs) throws SQLException {
            return new Sale(rs.getObject("id", UUID.class), rs.getObject("farm_id", UUID.class), rs.getString("crop"),
                    clean(rs.getBigDecimal("quantity")), rs.getString("unit"), rs.getLong("rate_paise"),
                    rs.getLong("total_paise"), rs.getLong("received_paise"), rs.getString("buyer_name"),
                    localDate(rs.getDate("sale_date")), rs.getString("notes"), rs.getTimestamp("deleted_at") != null,
                    rs.getLong("server_version"));
        }
    }
}
