package com.inviz.visioncerti.store;

import com.inviz.visioncerti.common.ApiException;
import org.springframework.http.HttpStatus;

/** Internal storage signals; translated to {@link ApiException} at the transaction boundary. */
public final class StoreException {

    private StoreException() {
    }

    /** A UNIQUE column already holds the value; the transaction is rolled back. */
    public static final class UniqueViolation extends RuntimeException {
        private final Kind<?> kind;
        private final String column;

        public UniqueViolation(Kind<?> kind, String column) {
            super("unique violation on " + (kind == null ? "?" : kind.table()) + "." + column, null, false, false);
            this.kind = kind;
            this.column = column;
        }

        public ApiException toApi() {
            return kind == null ? ApiException.conflict("STORAGE_UNIQUE_CONFLICT", "A conflicting record already exists")
                    : kind.conflict(column);
        }
    }

    /** The row changed since it was read (optimistic version check); the whole transaction may be retried. */
    public static final class ConcurrentModification extends RuntimeException {
        public ConcurrentModification(String table) {
            super("concurrent modification of " + table, null, false, false);
        }
    }

    /** Serialization failure or deadlock reported by the database; the whole transaction may be retried. */
    public static final class Retryable extends RuntimeException {
        public Retryable(String sqlState) {
            super("retryable storage failure " + sqlState, null, false, false);
        }
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                "Storage is temporarily unavailable; nothing was changed. Please retry.", true);
    }

    public static ApiException concurrentModification() {
        return new ApiException(HttpStatus.CONFLICT, "STORAGE_CONCURRENT_MODIFICATION",
                "The record was changed concurrently; nothing was changed. Please retry.", true);
    }
}
