package com.farmeraccounts.sync;

import com.farmeraccounts.sync.EntityModels.Versioned;
import com.farmeraccounts.sync.SyncModels.Change;
import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.OpResult;
import com.farmeraccounts.sync.SyncModels.OpStatus;
import com.farmeraccounts.sync.SyncModels.Operation;
import com.farmeraccounts.sync.SyncModels.PullResponse;
import com.farmeraccounts.sync.SyncModels.PushResponse;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies batches of client operations and serves incremental pulls.
 *
 * <ul>
 *   <li>Each operation runs in its own transaction, so one bad record never blocks the others.</li>
 *   <li>Operation ids are idempotency keys: a repeated id returns the stored result (DUPLICATE).</li>
 *   <li>Writes for one farmer are serialised with an advisory lock, which keeps server_version
 *       monotonic in commit order for that farmer, so a pull cursor can never skip a row.</li>
 * </ul>
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final int MAX_PULL = 500;

    private final Map<String, EntityHandler> handlers = new HashMap<>();
    private final List<EntityHandler> handlerList;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public SyncService(List<EntityHandler> handlerList, JdbcClient jdbc, PlatformTransactionManager txManager,
                       ObjectMapper json, Clock clock) {
        this.handlerList = handlerList;
        for (EntityHandler h : handlerList) {
            handlers.put(h.entityType() + "." + h.operation(), h);
        }
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    public PushResponse push(SyncContext ctx, List<Operation> ops) {
        List<OpResult> results = new ArrayList<>(ops.size());
        int applied = 0;
        int failed = 0;
        String lastError = null;
        for (Operation op : ops) {
            OpResult r = applyOne(ctx, op);
            results.add(r);
            if (r.status() == OpStatus.REJECTED) {
                failed++;
                lastError = r.code() + ": " + r.message();
            } else if (r.status() == OpStatus.APPLIED) {
                applied++;
            }
        }
        recordPush(ctx, applied, failed, lastError);
        return new PushResponse(results, clock.instant().toString());
    }

    private OpResult applyOne(SyncContext ctx, Operation op) {
        EntityHandler handler = handlers.get(op.type());
        String entityType = op.type().contains(".") ? op.type().substring(0, op.type().indexOf('.')) : op.type();
        if (handler == null) {
            return storeRejection(ctx, op, OpResult.rejected(op.opId(), entityType, "UNKNOWN_OPERATION",
                    "Unknown operation type", false));
        }
        try {
            return tx.execute(status -> {
                lockFarmer(ctx.farmerId());
                Optional<OpResult> previous = previousResult(ctx, op.opId());
                if (previous.isPresent()) {
                    return previous.get();
                }
                OpResult result = handler.apply(ctx, op);
                if (result.status() == OpStatus.APPLIED) {
                    storeResult(ctx, op, result);
                }
                return result;
            });
        } catch (OpRejectedException e) {
            OpResult r = OpResult.rejected(op.opId(), entityType, e.code(), e.getMessage(), e.retryable());
            return e.retryable() ? r : storeRejection(ctx, op, r);
        } catch (DataIntegrityViolationException e) {
            log.warn("Sync op {} ({}) violated a constraint: {}", op.opId(), op.type(), e.getMostSpecificCause().getMessage());
            return storeRejection(ctx, op, OpResult.rejected(op.opId(), entityType, "INVALID_DATA",
                    "The record is not valid", false));
        } catch (RuntimeException e) {
            log.error("Sync op {} ({}) failed unexpectedly", op.opId(), op.type(), e);
            return OpResult.rejected(op.opId(), entityType, "SERVER_ERROR", "Temporary server problem", true);
        }
    }

    private void lockFarmer(UUID farmerId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:f, 0))").param("f", farmerId.toString())
                .query(Object.class).single();
    }

    private Optional<OpResult> previousResult(SyncContext ctx, UUID opId) {
        record Prev(UUID farmerId, String response) {}
        return jdbc.sql("SELECT farmer_id, response::text AS response FROM sync_operations WHERE op_id = :id")
                .param("id", opId)
                .query((rs, n) -> new Prev(rs.getObject("farmer_id", UUID.class), rs.getString("response")))
                .optional()
                .map(prev -> {
                    if (!prev.farmerId().equals(ctx.farmerId())) {
                        return OpResult.rejected(opId, null, "FORBIDDEN", "Operation id belongs to another account", false);
                    }
                    OpResult stored = json.readValue(prev.response(), OpResult.class);
                    return stored.status() == OpStatus.APPLIED ? stored.withStatus(OpStatus.DUPLICATE) : stored;
                });
    }

    private void storeResult(SyncContext ctx, Operation op, OpResult result) {
        jdbc.sql("""
                INSERT INTO sync_operations (op_id, farmer_id, device_id, op_type, entity_id, result_status, response, created_at)
                VALUES (:id, :f, :d, :t, :e, :s, CAST(:r AS jsonb), :now)
                """)
                .param("id", op.opId()).param("f", ctx.farmerId()).param("d", ctx.deviceId()).param("t", op.type())
                .param("e", op.entityId()).param("s", result.status().name())
                .param("r", json.writeValueAsString(result)).param("now", Timestamp.from(clock.instant()))
                .update();
    }

    /** Permanent rejections are remembered so a resend gets the same answer (and admins can count them). */
    private OpResult storeRejection(SyncContext ctx, Operation op, OpResult result) {
        try {
            tx.executeWithoutResult(status -> {
                if (previousResult(ctx, op.opId()).isEmpty()) {
                    storeResult(ctx, op, result);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not record rejected sync op {}", op.opId(), e);
        }
        return result;
    }

    private void recordPush(SyncContext ctx, int applied, int failed, String lastError) {
        if (ctx.deviceId() == null) {
            return;
        }
        jdbc.sql("""
                INSERT INTO synchronization_metadata (farmer_id, device_id, last_seen_at, last_push_at, ops_applied, ops_failed, last_error)
                VALUES (:f, :d, :now, :now, :a, :x, :err)
                ON CONFLICT (farmer_id, device_id) DO UPDATE SET last_seen_at = :now, last_push_at = :now,
                    ops_applied = synchronization_metadata.ops_applied + :a,
                    ops_failed = synchronization_metadata.ops_failed + :x,
                    last_error = COALESCE(:err, synchronization_metadata.last_error)
                """)
                .param("f", ctx.farmerId()).param("d", ctx.deviceId()).param("now", Timestamp.from(clock.instant()))
                .param("a", applied).param("x", failed).param("err", lastError)
                .update();
    }

    public PullResponse pull(SyncContext ctx, long cursor, int requestedLimit, String appVersion) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PULL));
        record Row(String type, Versioned<?> data) {}
        List<Row> rows = new ArrayList<>();
        for (EntityHandler h : handlerList) {
            for (Versioned<?> v : h.changedSince(ctx.farmerId(), cursor, limit + 1)) {
                rows.add(new Row(h.entityType(), v));
            }
        }
        rows.sort(Comparator.comparingLong(r -> r.data().serverVersion()));
        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;
        long next = page.isEmpty() ? cursor : page.get(page.size() - 1).data().serverVersion();
        List<Change> changes = page.stream().map(r -> new Change(r.type(), r.data())).toList();
        recordPull(ctx, next, appVersion);
        return new PullResponse(changes, next, hasMore, clock.instant().toString());
    }

    private void recordPull(SyncContext ctx, long cursor, String appVersion) {
        if (ctx.deviceId() == null) {
            return;
        }
        jdbc.sql("""
                INSERT INTO synchronization_metadata (farmer_id, device_id, app_version, last_seen_at, last_pull_at, last_pull_cursor)
                VALUES (:f, :d, :ver, :now, :now, :c)
                ON CONFLICT (farmer_id, device_id) DO UPDATE SET last_seen_at = :now, last_pull_at = :now,
                    last_pull_cursor = :c, app_version = COALESCE(:ver, synchronization_metadata.app_version)
                """)
                .param("f", ctx.farmerId()).param("d", ctx.deviceId())
                .param("ver", appVersion == null ? null : appVersion.substring(0, Math.min(20, appVersion.length())))
                .param("now", Timestamp.from(clock.instant())).param("c", cursor)
                .update();
    }
}
