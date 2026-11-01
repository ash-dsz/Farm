package com.farmeraccounts.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Wire format of the synchronisation API. */
public final class SyncModels {

    private SyncModels() {}

    public enum OpStatus {
        /** The change is stored on the server (or was already identical). */
        APPLIED,
        /** The operation id was processed before; the original result is returned. */
        DUPLICATE,
        /** The server copy changed since the client last saw it; nothing was written. */
        CONFLICT,
        /** The operation can never succeed as sent (or, if retryable, not yet). */
        REJECTED
    }

    /**
     * One client change.
     *
     * @param opId        client-generated idempotency key, unique per change
     * @param type        e.g. "worker.upsert", "transaction.create"
     * @param baseVersion the server version the client's copy was based on (null for new records)
     */
    public record Operation(@NotNull UUID opId, @NotBlank @Size(max = 40) String type, @NotNull UUID entityId,
                            Long baseVersion, @NotNull JsonNode payload) {}

    public record PushRequest(@NotNull @Size(max = 200) List<@Valid @NotNull Operation> operations) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpResult(UUID opId, OpStatus status, String entityType, Object entity, String code, String message,
                           Boolean retryable) {

        public static OpResult applied(UUID opId, String entityType, Object entity) {
            return new OpResult(opId, OpStatus.APPLIED, entityType, entity, null, null, null);
        }

        public static OpResult conflict(UUID opId, String entityType, Object serverEntity) {
            return new OpResult(opId, OpStatus.CONFLICT, entityType, serverEntity, "CONFLICT",
                    "This record was changed on another device", null);
        }

        public static OpResult rejected(UUID opId, String entityType, String code, String message, boolean retryable) {
            return new OpResult(opId, OpStatus.REJECTED, entityType, null, code, message, retryable);
        }

        public OpResult withStatus(OpStatus s) {
            return new OpResult(opId, s, entityType, entity, code, message, retryable);
        }
    }

    public record PushResponse(List<OpResult> results, String serverTime) {}

    public record Change(String type, Object data) {}

    public record PullResponse(List<Change> changes, long cursor, boolean hasMore, String serverTime) {}

    /** Who is syncing. */
    public record SyncContext(UUID farmerId, String deviceId) {}

    /** Thrown by handlers to reject an operation without writing anything. */
    public static class OpRejectedException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public OpRejectedException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }

        public static OpRejectedException invalid(String message) {
            return new OpRejectedException("VALIDATION_FAILED", message, false);
        }

        public static OpRejectedException missingParent(String message) {
            return new OpRejectedException("MISSING_PARENT", message, true);
        }

        public static OpRejectedException forbidden() {
            return new OpRejectedException("FORBIDDEN", "This record belongs to another account", false);
        }
    }
}
