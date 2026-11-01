package com.farmeraccounts.sync;

import static com.farmeraccounts.sync.SyncSupport.clean;
import static com.farmeraccounts.sync.SyncSupport.localDate;
import static com.farmeraccounts.sync.SyncSupport.sqlDate;

import com.farmeraccounts.common.PhoneNumbers;
import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.sync.EntityModels.Farm;
import com.farmeraccounts.sync.EntityModels.Field;
import com.farmeraccounts.sync.EntityModels.Worker;
import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Handlers for farms, fields and workers (editable master data). */
public final class FarmHandlers {

    private FarmHandlers() {}

    private static final String DELETED_AT =
            "CASE WHEN :deleted THEN COALESCE(deleted_at, now()) ELSE NULL END";

    @Component
    public static class FarmHandler extends UpsertHandler<Farm> {

        public FarmHandler(SyncSupport support) {
            super(support, Farm.class);
        }

        @Override
        public String entityType() {
            return "farm";
        }

        @Override
        protected Optional<Stored<Farm>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM farms WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected Farm normalize(SyncContext ctx, Farm f) {
            return new Farm(f.id(), clean(f.name()), clean(f.village()), clean(f.district()), clean(f.state()),
                    clean(f.totalAcreage()), f.primaryCrop(), clean(f.notes()), f.deleted(), null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, Farm data) {}

        @Override
        protected void insert(SyncContext ctx, Farm f, long version) {
            support.jdbc().sql("""
                    INSERT INTO farms (id, farmer_id, name, village, district, state, total_acreage, primary_crop, notes,
                                       deleted_at, server_version)
                    VALUES (:id, :farmer, :name, :village, :district, :state, :acreage, :crop, :notes,
                            CASE WHEN :deleted THEN now() ELSE NULL END, :v)
                    """)
                    .param("id", f.id()).param("farmer", ctx.farmerId()).param("name", f.name())
                    .param("village", f.village()).param("district", f.district()).param("state", f.state())
                    .param("acreage", f.totalAcreage()).param("crop", f.primaryCrop()).param("notes", f.notes())
                    .param("deleted", f.deleted()).param("v", version)
                    .update();
        }

        @Override
        protected void update(SyncContext ctx, Farm f, long version) {
            support.jdbc().sql("""
                    UPDATE farms SET name = :name, village = :village, district = :district, state = :state,
                        total_acreage = :acreage, primary_crop = :crop, notes = :notes, deleted_at = %s,
                        server_version = :v, updated_at = now()
                    WHERE id = :id
                    """.formatted(DELETED_AT))
                    .param("id", f.id()).param("name", f.name())
                    .param("village", f.village()).param("district", f.district()).param("state", f.state())
                    .param("acreage", f.totalAcreage()).param("crop", f.primaryCrop()).param("notes", f.notes())
                    .param("deleted", f.deleted()).param("v", version)
                    .update();
        }

        @Override
        public List<Farm> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM farms WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Farm map(ResultSet rs) throws SQLException {
            return new Farm(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("village"),
                    rs.getString("district"), rs.getString("state"), clean(rs.getBigDecimal("total_acreage")),
                    rs.getString("primary_crop"), rs.getString("notes"), rs.getTimestamp("deleted_at") != null,
                    rs.getLong("server_version"));
        }
    }

    @Component
    public static class FieldHandler extends UpsertHandler<Field> {

        public FieldHandler(SyncSupport support) {
            super(support, Field.class);
        }

        @Override
        public String entityType() {
            return "field";
        }

        @Override
        protected Optional<Stored<Field>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM fields WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected Field normalize(SyncContext ctx, Field f) {
            return new Field(f.id(), f.farmId(), clean(f.name()), clean(f.areaAcres()), f.crop(),
                    clean(f.description()), f.deleted(), null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, Field f) {
            support.requireFarm(ctx, f.farmId());
            find(f.id()).ifPresent(existing -> {
                if (!existing.data().farmId().equals(f.farmId())) {
                    throw OpRejectedException.invalid("A field cannot be moved to another farm");
                }
            });
        }

        @Override
        protected void insert(SyncContext ctx, Field f, long version) {
            support.jdbc().sql("""
                    INSERT INTO fields (id, farmer_id, farm_id, name, area_acres, crop, description, deleted_at, server_version)
                    VALUES (:id, :farmer, :farm, :name, :area, :crop, :description,
                            CASE WHEN :deleted THEN now() ELSE NULL END, :v)
                    """)
                    .param("id", f.id()).param("farmer", ctx.farmerId()).param("farm", f.farmId())
                    .param("name", f.name()).param("area", f.areaAcres()).param("crop", f.crop())
                    .param("description", f.description()).param("deleted", f.deleted()).param("v", version)
                    .update();
        }

        @Override
        protected void update(SyncContext ctx, Field f, long version) {
            support.jdbc().sql("""
                    UPDATE fields SET name = :name, area_acres = :area, crop = :crop, description = :description,
                        deleted_at = %s, server_version = :v, updated_at = now()
                    WHERE id = :id
                    """.formatted(DELETED_AT))
                    .param("id", f.id()).param("name", f.name()).param("area", f.areaAcres()).param("crop", f.crop())
                    .param("description", f.description()).param("deleted", f.deleted()).param("v", version)
                    .update();
        }

        @Override
        public List<Field> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM fields WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Field map(ResultSet rs) throws SQLException {
            return new Field(rs.getObject("id", UUID.class), rs.getObject("farm_id", UUID.class), rs.getString("name"),
                    clean(rs.getBigDecimal("area_acres")), rs.getString("crop"), rs.getString("description"),
                    rs.getTimestamp("deleted_at") != null, rs.getLong("server_version"));
        }
    }

    @Component
    public static class WorkerHandler extends UpsertHandler<Worker> {

        public WorkerHandler(SyncSupport support) {
            super(support, Worker.class);
        }

        @Override
        public String entityType() {
            return "worker";
        }

        @Override
        protected Optional<Stored<Worker>> find(UUID id) {
            return support.jdbc().sql("SELECT * FROM workers WHERE id = :id").param("id", id)
                    .query((rs, n) -> new Stored<>(rs.getObject("farmer_id", UUID.class), map(rs))).optional();
        }

        @Override
        protected Worker normalize(SyncContext ctx, Worker w) {
            String phone;
            try {
                phone = PhoneNumbers.normalizeOptional(w.phone());
            } catch (ApiException e) {
                throw OpRejectedException.invalid("Worker phone number is not valid");
            }
            support.requireReasonableDate(w.joiningDate(), "Joining");
            return new Worker(w.id(), w.farmId(), clean(w.name()), phone, w.dailyWagePaise(), w.joiningDate(),
                    w.active(), clean(w.notes()), null);
        }

        @Override
        protected void checkReferences(SyncContext ctx, Worker w) {
            support.requireFarm(ctx, w.farmId());
            find(w.id()).ifPresent(existing -> {
                if (!existing.data().farmId().equals(w.farmId())) {
                    throw OpRejectedException.invalid("A worker cannot be moved to another farm");
                }
            });
        }

        @Override
        protected void insert(SyncContext ctx, Worker w, long version) {
            support.jdbc().sql("""
                    INSERT INTO workers (id, farmer_id, farm_id, name, phone, daily_wage_paise, joining_date, active,
                                         notes, server_version)
                    VALUES (:id, :farmer, :farm, :name, :phone, :wage, :joined, :active, :notes, :v)
                    """)
                    .param("id", w.id()).param("farmer", ctx.farmerId()).param("farm", w.farmId())
                    .param("name", w.name()).param("phone", w.phone()).param("wage", w.dailyWagePaise())
                    .param("joined", sqlDate(w.joiningDate())).param("active", w.active()).param("notes", w.notes())
                    .param("v", version)
                    .update();
        }

        @Override
        protected void update(SyncContext ctx, Worker w, long version) {
            support.jdbc().sql("""
                    UPDATE workers SET name = :name, phone = :phone, daily_wage_paise = :wage, joining_date = :joined,
                        active = :active, notes = :notes, server_version = :v, updated_at = now()
                    WHERE id = :id
                    """)
                    .param("id", w.id()).param("name", w.name()).param("phone", w.phone())
                    .param("wage", w.dailyWagePaise()).param("joined", sqlDate(w.joiningDate()))
                    .param("active", w.active()).param("notes", w.notes()).param("v", version)
                    .update();
        }

        @Override
        public List<Worker> changedSince(UUID farmerId, long after, int limit) {
            return support.jdbc().sql("""
                    SELECT * FROM workers WHERE farmer_id = :f AND server_version > :after
                    ORDER BY server_version LIMIT :limit
                    """)
                    .param("f", farmerId).param("after", after).param("limit", limit)
                    .query((rs, n) -> map(rs)).list();
        }

        public static Worker map(ResultSet rs) throws SQLException {
            return new Worker(rs.getObject("id", UUID.class), rs.getObject("farm_id", UUID.class), rs.getString("name"),
                    rs.getString("phone"), rs.getLong("daily_wage_paise"), localDate(rs.getDate("joining_date")),
                    rs.getBoolean("active"), rs.getString("notes"), rs.getLong("server_version"));
        }
    }
}
