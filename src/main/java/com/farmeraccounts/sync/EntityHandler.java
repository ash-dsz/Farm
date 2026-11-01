package com.farmeraccounts.sync;

import com.farmeraccounts.sync.EntityModels.Versioned;
import com.farmeraccounts.sync.SyncModels.OpResult;
import com.farmeraccounts.sync.SyncModels.Operation;
import com.farmeraccounts.sync.SyncModels.SyncContext;
import java.util.List;
import java.util.UUID;

/** Applies pushed operations for one entity type and lists its changes for pull. */
public interface EntityHandler {

    /** Entity name used in operation types ("worker" for "worker.upsert") and pull changes. */
    String entityType();

    /** "upsert" for editable records, "create" for append-only financial records. */
    String operation();

    /** Applies the operation inside the caller's transaction. May throw OpRejectedException. */
    OpResult apply(SyncContext ctx, Operation op);

    /** Rows of this farmer changed after {@code afterVersion}, oldest first. */
    List<? extends Versioned<?>> changedSince(UUID farmerId, long afterVersion, int limit);
}
