package com.inviz.visioncerti.store;

import com.inviz.visioncerti.common.ApiException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * How one aggregate type is stored: its table in schema {@code certi_rt}, identity, tenant, optional expiry and the
 * typed lookup columns derived from it. A lookup column is NULL when it does not apply (e.g. {@code active_doctor_id}
 * only while ACTIVE), so a UNIQUE column also expresses "unique while in that state". The whole aggregate is stored
 * as JSON in {@code payload}; columns are derived on every write and never edited separately.
 */
public final class Kind<A> {

    /** Pseudo-column usable in lookups: the aggregate's tenant. */
    public static final String TENANT = "tenant_id";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final String table;
    private final Class<A> type;
    private final Function<A, UUID> id;
    private final Function<A, String> tenant;
    private final Function<A, Instant> expiresAt;
    private final Map<String, Function<A, String>> columns;
    private final Map<String, Supplier<ApiException>> unique;
    private final boolean immutable;

    private Kind(Builder<A> b) {
        this.table = b.table;
        this.type = b.type;
        this.id = Objects.requireNonNull(b.id);
        this.tenant = b.tenant == null ? a -> null : b.tenant;
        this.expiresAt = b.expiresAt == null ? a -> null : b.expiresAt;
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(b.columns));
        this.unique = Collections.unmodifiableMap(new LinkedHashMap<>(b.unique));
        this.immutable = b.immutable;
    }

    public static <A> Builder<A> of(String table, Class<A> type) {
        return new Builder<>(table, type);
    }

    public String table() {
        return table;
    }

    public Class<A> type() {
        return type;
    }

    public UUID idOf(A value) {
        return id.apply(value);
    }

    public String tenantOf(A value) {
        return tenant.apply(value);
    }

    public Instant expiresAtOf(A value) {
        return expiresAt.apply(value);
    }

    public Map<String, Function<A, String>> columns() {
        return columns;
    }

    public boolean isUnique(String column) {
        return unique.containsKey(column);
    }

    public boolean immutable() {
        return immutable;
    }

    /** The business error for a uniqueness conflict on {@code column}. */
    public ApiException conflict(String column) {
        Supplier<ApiException> s = column == null ? null : unique.get(column);
        return s != null ? s.get()
                : ApiException.conflict("STORAGE_UNIQUE_CONFLICT", "A conflicting record already exists");
    }

    /** Validates a lookup column name (it becomes part of SQL text, so only declared names are allowed). */
    String column(String name) {
        if (TENANT.equals(name) || columns.containsKey(name)) {
            return name;
        }
        throw new IllegalArgumentException("Unknown column " + name + " for " + table);
    }

    Map<String, String> columnValues(A value) {
        Map<String, String> values = new LinkedHashMap<>();
        columns.forEach((name, f) -> values.put(name, f.apply(value)));
        return values;
    }

    @Override
    public String toString() {
        return "Kind[" + table + "]";
    }

    public static final class Builder<A> {
        private final String table;
        private final Class<A> type;
        private Function<A, UUID> id;
        private Function<A, String> tenant;
        private Function<A, Instant> expiresAt;
        private final Map<String, Function<A, String>> columns = new LinkedHashMap<>();
        private final Map<String, Supplier<ApiException>> unique = new LinkedHashMap<>();
        private boolean immutable;

        private Builder(String table, Class<A> type) {
            this.table = requireName(table);
            this.type = type;
        }

        public Builder<A> id(Function<A, UUID> id) {
            this.id = id;
            return this;
        }

        public Builder<A> tenant(Function<A, String> tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder<A> expiresAt(Function<A, Instant> expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder<A> column(String name, Function<A, String> value) {
            columns.put(requireName(name), value);
            return this;
        }

        public Builder<A> unique(String name, Function<A, String> value, Supplier<ApiException> conflict) {
            column(name, value);
            unique.put(name, conflict);
            return this;
        }

        /** Rows are insert-only (the SQL schema also rejects UPDATE/DELETE with a trigger). */
        public Builder<A> immutable() {
            this.immutable = true;
            return this;
        }

        public Kind<A> build() {
            return new Kind<>(this);
        }

        private static String requireName(String name) {
            if (name == null || !NAME.matcher(name).matches() || name.equals(TENANT)) {
                throw new IllegalArgumentException("Invalid storage name: " + name);
            }
            return name;
        }
    }
}
