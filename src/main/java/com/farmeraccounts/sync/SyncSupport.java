package com.farmeraccounts.sync;

import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared plumbing for sync handlers: parsing, validation, ownership checks, versions and audit. */
@Component
public class SyncSupport {

    /** Farm dates are Indian calendar days. */
    public static final ZoneId FARM_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private final JdbcClient jdbc;
    private final Validator validator;
    private final ObjectMapper json;
    private final Clock clock;

    public SyncSupport(JdbcClient jdbc, Validator validator, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.json = json;
        this.clock = clock;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public <T> T parse(JsonNode payload, Class<T> type) {
        T value;
        try {
            value = json.treeToValue(payload, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw OpRejectedException.invalid("Malformed " + type.getSimpleName() + " data");
        }
        if (value == null) {
            throw OpRejectedException.invalid("Missing data");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw OpRejectedException.invalid(msg);
        }
        return value;
    }

    public long nextVersion() {
        return jdbc.sql("SELECT nextval('change_seq')").query(Long.class).single();
    }

    public String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public void audit(SyncContext ctx, String entityType, UUID entityId, String action, Object oldData, Object newData) {
        jdbc.sql("""
                INSERT INTO record_audit_log (farmer_id, entity_type, entity_id, action, old_data, new_data, device_id)
                VALUES (:f, :t, :id, :a, CAST(:old AS jsonb), CAST(:new AS jsonb), :d)
                """)
                .param("f", ctx.farmerId())
                .param("t", entityType)
                .param("id", entityId)
                .param("a", action)
                .param("old", toJson(oldData))
                .param("new", toJson(newData))
                .param("d", ctx.deviceId())
                .update();
    }

    // ---- ownership / reference checks ----

    /** Owner of a row in a farmer-owned table, if the row exists. */
    public Optional<UUID> ownerOf(String table, UUID id) {
        return jdbc.sql("SELECT farmer_id FROM " + table + " WHERE id = :id").param("id", id).query(UUID.class).optional();
    }

    public void requireFarm(SyncContext ctx, UUID farmId) {
        UUID owner = ownerOf("farms", farmId)
                .orElseThrow(() -> OpRejectedException.missingParent("Farm not found yet"));
        if (!owner.equals(ctx.farmerId())) {
            throw OpRejectedException.forbidden();
        }
    }

    public void requireWorkerInFarm(SyncContext ctx, UUID workerId, UUID farmId) {
        record W(UUID farmerId, UUID farmId) {}
        W w = jdbc.sql("SELECT farmer_id, farm_id FROM workers WHERE id = :id").param("id", workerId)
                .query((rs, n) -> new W(rs.getObject("farmer_id", UUID.class), rs.getObject("farm_id", UUID.class)))
                .optional()
                .orElseThrow(() -> OpRejectedException.missingParent("Worker not found yet"));
        if (!w.farmerId().equals(ctx.farmerId())) {
            throw OpRejectedException.forbidden();
        }
        if (farmId != null && !w.farmId().equals(farmId)) {
            throw OpRejectedException.invalid("Worker does not belong to this farm");
        }
    }

    public UUID farmOfWorker(SyncContext ctx, UUID workerId) {
        requireWorkerInFarm(ctx, workerId, null);
        return jdbc.sql("SELECT farm_id FROM workers WHERE id = :id").param("id", workerId).query(UUID.class).single();
    }

    public void requireFieldInFarm(SyncContext ctx, UUID fieldId, UUID farmId) {
        record F(UUID farmerId, UUID farmId) {}
        F f = jdbc.sql("SELECT farmer_id, farm_id FROM fields WHERE id = :id").param("id", fieldId)
                .query((rs, n) -> new F(rs.getObject("farmer_id", UUID.class), rs.getObject("farm_id", UUID.class)))
                .optional()
                .orElseThrow(() -> OpRejectedException.missingParent("Field not found yet"));
        if (!f.farmerId().equals(ctx.farmerId())) {
            throw OpRejectedException.forbidden();
        }
        if (!f.farmId().equals(farmId)) {
            throw OpRejectedException.invalid("Field does not belong to this farm");
        }
    }

    // ---- value rules ----

    public LocalDate today() {
        return LocalDate.now(clock.withZone(FARM_ZONE));
    }

    /** Dates for work, payments, expenses and sales: not before 2000 and not in the future. */
    public void requirePastOrToday(LocalDate date, String what) {
        // One day of tolerance for phones whose clock or zone is slightly off.
        if (date.isBefore(MIN_DATE) || date.isAfter(today().plusDays(1))) {
            throw OpRejectedException.invalid(what + " date is not valid");
        }
    }

    public void requireReasonableDate(LocalDate date, String what) {
        if (date.isBefore(MIN_DATE) || date.isAfter(today().plusYears(1))) {
            throw OpRejectedException.invalid(what + " date is not valid");
        }
    }

    public static BigDecimal clean(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal s = value.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    public static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    public static LocalDate localDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
