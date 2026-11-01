package com.farmeraccounts.sync;

import com.farmeraccounts.sync.EntityModels.Versioned;
import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.OpResult;
import com.farmeraccounts.sync.SyncModels.Operation;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Financial records that are written once and never changed (the tables also reject UPDATE and
 * DELETE with a trigger). Re-sending the same record is idempotent; re-using an id for different
 * content is rejected instead of overwriting anything.
 */
public abstract class AppendOnlyHandler<T extends Versioned<T>> implements EntityHandler {

    protected record Stored<T>(UUID farmerId, T data) {}

    protected final SyncSupport support;
    private final Class<T> type;

    protected AppendOnlyHandler(SyncSupport support, Class<T> type) {
        this.support = support;
        this.type = type;
    }

    @Override
    public String operation() {
        return "create";
    }

    protected abstract Optional<Stored<T>> find(UUID id);

    protected abstract T normalize(SyncContext ctx, T data);

    protected abstract void checkReferences(SyncContext ctx, T data);

    protected abstract void insert(SyncContext ctx, T data, long version);

    /** Compares business content, ignoring server-assigned fields. */
    protected boolean sameContent(T stored, T incoming) {
        return stored.withVersion(null).equals(incoming.withVersion(null));
    }

    @Override
    public OpResult apply(SyncContext ctx, Operation op) {
        T incoming = support.parse(op.payload(), type);
        if (!incoming.id().equals(op.entityId())) {
            throw OpRejectedException.invalid("Entity id does not match the operation");
        }
        incoming = normalize(ctx, incoming).withVersion(null);

        Optional<Stored<T>> existing = find(incoming.id());
        if (existing.isPresent()) {
            if (!existing.get().farmerId().equals(ctx.farmerId())) {
                throw OpRejectedException.forbidden();
            }
            if (sameContent(existing.get().data(), incoming)) {
                return OpResult.applied(op.opId(), entityType(), existing.get().data());
            }
            throw new OpRejectedException("ID_REUSED",
                    "A different record with this id already exists; financial records cannot be changed", false);
        }
        checkReferences(ctx, incoming);
        long version = support.nextVersion();
        insert(ctx, incoming, version);
        T saved = find(incoming.id()).orElseThrow().data();
        support.audit(ctx, entityType(), saved.id(), "CREATE", null, saved);
        return OpResult.applied(op.opId(), entityType(), saved);
    }
}
