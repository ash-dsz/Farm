package com.farmeraccounts.sync;

import com.farmeraccounts.sync.EntityModels.Versioned;
import com.farmeraccounts.sync.SyncModels.OpRejectedException;
import com.farmeraccounts.sync.SyncModels.OpResult;
import com.farmeraccounts.sync.SyncModels.Operation;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Editable records with optimistic concurrency. An update is applied only when the client's
 * {@code baseVersion} matches the server copy; otherwise the server copy is returned as a CONFLICT
 * and nothing is overwritten. Re-sending identical content is always accepted.
 */
public abstract class UpsertHandler<T extends Versioned<T>> implements EntityHandler {

    /** A stored row plus its owner. */
    protected record Stored<T>(UUID farmerId, T data) {}

    protected final SyncSupport support;
    private final Class<T> type;

    protected UpsertHandler(SyncSupport support, Class<T> type) {
        this.support = support;
        this.type = type;
    }

    @Override
    public String operation() {
        return "upsert";
    }

    protected abstract Optional<Stored<T>> find(UUID id);

    /** Trim/normalise values and check rules that bean validation cannot express. */
    protected abstract T normalize(SyncContext ctx, T data);

    protected abstract void checkReferences(SyncContext ctx, T data);

    protected abstract void insert(SyncContext ctx, T data, long version);

    protected abstract void update(SyncContext ctx, T data, long version);

    @Override
    public OpResult apply(SyncContext ctx, Operation op) {
        T incoming = support.parse(op.payload(), type);
        if (!incoming.id().equals(op.entityId())) {
            throw OpRejectedException.invalid("Entity id does not match the operation");
        }
        incoming = normalize(ctx, incoming).withVersion(null);

        Optional<Stored<T>> existing = find(incoming.id());
        if (existing.isPresent() && !existing.get().farmerId().equals(ctx.farmerId())) {
            throw OpRejectedException.forbidden();
        }
        checkReferences(ctx, incoming);

        if (existing.isEmpty()) {
            long version = support.nextVersion();
            insert(ctx, incoming, version);
            T saved = incoming.withVersion(version);
            support.audit(ctx, entityType(), saved.id(), "CREATE", null, saved);
            return OpResult.applied(op.opId(), entityType(), saved);
        }

        T current = existing.get().data();
        if (current.withVersion(null).equals(incoming)) {
            return OpResult.applied(op.opId(), entityType(), current);
        }
        if (op.baseVersion() == null || !op.baseVersion().equals(current.serverVersion())) {
            return OpResult.conflict(op.opId(), entityType(), current);
        }
        long version = support.nextVersion();
        update(ctx, incoming, version);
        T saved = incoming.withVersion(version);
        support.audit(ctx, entityType(), saved.id(), "UPDATE", current, saved);
        return OpResult.applied(op.opId(), entityType(), saved);
    }
}
