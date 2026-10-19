package com.inviz.visioncerti.store;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL {@link Store} over schema {@code certi_rt} (migration {@code V005__production_runtime.sql}).
 *
 * <p>Each aggregate is one row: {@code id, tenant_id, version, payload jsonb, expires_at} plus derived lookup
 * columns with real UNIQUE constraints, so concurrent instances cannot create duplicates. Updates are version CAS;
 * {@code lock*} reads use {@code FOR UPDATE} bounded by {@code lock_timeout}; {@link Tx#lock} is a
 * transaction-scoped advisory lock. Serialization failures, deadlocks and version conflicts retry the whole
 * transaction (it never contains network I/O); every other database error rolls back and fails closed with 503.
 * Logs contain SQL states and table names only, never values.
 */
public final class PostgresStore implements Store, AutoCloseable {

    public static final String SCHEMA = "certi_rt";
    public static final int SCHEMA_VERSION = 9;
    private static final int MAX_ATTEMPTS = 3;
    private static final Logger LOG = LoggerFactory.getLogger(PostgresStore.class);

    private final DataSource dataSource;
    private final String lockTimeout;
    private final ThreadLocal<PgTx> current = new ThreadLocal<>();
    private final Map<String, Kind<?>> kindsByTable = new java.util.concurrent.ConcurrentHashMap<>();

    public PostgresStore(DataSource dataSource, java.time.Duration lockTimeout) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.lockTimeout = Math.max(100, lockTimeout.toMillis()) + "ms";
    }

    @Override
    public String mode() {
        return MODE_POSTGRESQL;
    }

    /** Closes the connection pool (called by the container on shutdown). */
    @Override
    public void close() throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Override
    public boolean inTransaction() {
        return current.get() != null;
    }

    /** Fails unless {@code certi_rt.schema_info} reports {@link #SCHEMA_VERSION}. */
    public void verifySchema() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT version FROM " + SCHEMA + ".schema_info WHERE component = 'runtime'")) {
            try (ResultSet rs = ps.executeQuery()) {
                int version = rs.next() ? rs.getInt(1) : -1;
                c.rollback();
                if (version != SCHEMA_VERSION) {
                    throw new IllegalStateException("FAIL_CLOSED: certi_rt.schema_info runtime version is " + version
                            + ", expected " + SCHEMA_VERSION + " (apply database/migrations/V009__medical_institutions.sql)");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FAIL_CLOSED: PostgreSQL runtime schema check failed (SQLState "
                    + e.getSQLState() + "); apply V005 to V009 and check VISIONCERTI_DB_* settings", e);
        }
    }

    /**
     * Prod check of the role the pool actually connected as (session user, including roles it can assume): it must
     * not be or be able to become a superuser or a BYPASSRLS role, must not own (or be a member of the owner of)
     * schema {@code certi_rt}, and must be a member of {@code certi_app}. Fails closed; messages name the rule only.
     */
    public void verifyRuntimeRole() {
        String sql = "SELECT"
                + " EXISTS (SELECT 1 FROM pg_roles s WHERE s.rolsuper AND pg_has_role(session_user, s.oid, 'MEMBER')),"
                + " EXISTS (SELECT 1 FROM pg_roles s WHERE s.rolbypassrls AND pg_has_role(session_user, s.oid, 'MEMBER')),"
                + " EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = '" + SCHEMA + "'"
                + "   AND pg_has_role(session_user, n.nspowner, 'MEMBER')),"
                + " EXISTS (SELECT 1 FROM pg_roles a WHERE a.rolname = 'certi_app'"
                + "   AND pg_has_role(session_user, a.oid, 'MEMBER')),"
                + " session_user = current_user";
        boolean superuser;
        boolean bypassRls;
        boolean owner;
        boolean appMember;
        boolean sameUser;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            superuser = rs.getBoolean(1);
            bypassRls = rs.getBoolean(2);
            owner = rs.getBoolean(3);
            appMember = rs.getBoolean(4);
            sameUser = rs.getBoolean(5);
            c.rollback();
        } catch (SQLException e) {
            throw new IllegalStateException("FAIL_CLOSED: PostgreSQL runtime role check failed (SQLState "
                    + e.getSQLState() + ")", e);
        }
        if (superuser) {
            throw new IllegalStateException("FAIL_CLOSED: the runtime database login is (or can become) a superuser");
        }
        if (bypassRls) {
            throw new IllegalStateException("FAIL_CLOSED: the runtime database login has (or can assume) BYPASSRLS");
        }
        if (owner) {
            throw new IllegalStateException("FAIL_CLOSED: the runtime database login owns (or is a member of the owner"
                    + " of) schema " + SCHEMA + "; use a separate login that is only a member of certi_app");
        }
        if (!appMember) {
            throw new IllegalStateException("FAIL_CLOSED: the runtime database login is not a member of certi_app");
        }
        if (!sameUser) {
            throw new IllegalStateException("FAIL_CLOSED: the runtime database session runs under a different role");
        }
    }

    @Override
    public <T> T tx(Function<Tx, T> work) {
        PgTx existing = current.get();
        if (existing != null) {
            return work.apply(existing);
        }
        for (int attempt = 1;; attempt++) {
            try {
                return attempt(work);
            } catch (StoreException.Retryable | StoreException.ConcurrentModification e) {
                if (attempt >= MAX_ATTEMPTS) {
                    LOG.warn("storage transaction gave up after {} attempts: {}", attempt, e.getMessage());
                    throw e instanceof StoreException.ConcurrentModification ? StoreException.concurrentModification()
                            : StoreException.unavailable();
                }
            }
        }
    }

    private <T> T attempt(Function<Tx, T> work) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            LOG.warn("storage connection unavailable (SQLState {})", e.getSQLState());
            throw StoreException.unavailable();
        }
        PgTx tx = new PgTx(connection);
        current.set(tx);
        try {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '" + lockTimeout + "'");
                s.execute("SET LOCAL statement_timeout = '30s'");
            }
            T result = null;
            ApiException refusal = null;
            try {
                result = work.apply(tx);
            } catch (ApiException e) {
                refusal = e;
            } catch (StoreException.UniqueViolation e) {
                rollback(connection);
                throw e.toApi();
            } catch (RuntimeException | Error e) {
                rollback(connection);
                throw e;
            }
            if (!tx.poisoned) {
                try {
                    tx.runBeforeCommit();
                } catch (StoreException.UniqueViolation e) {
                    rollback(connection);
                    throw e.toApi();
                } catch (RuntimeException | Error e) {
                    rollback(connection);
                    throw e;
                }
            }
            if (tx.poisoned) {
                rollback(connection);
                if (tx.violation != null) {
                    throw tx.violation.toApi();
                }
                throw refusal != null && "STORAGE_UNAVAILABLE".equals(refusal.code()) ? refusal
                        : StoreException.unavailable();
            }
            try {
                connection.commit();
            } catch (SQLException e) {
                rollback(connection);
                throw translate(e, null, tx);
            }
            if (refusal != null) {
                throw refusal;
            }
            return result;
        } catch (SQLException e) {
            rollback(connection);
            throw translate(e, null, tx);
        } finally {
            current.remove();
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("storage connection close failed (SQLState {})", e.getSQLState());
            }
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.warn("storage rollback failed (SQLState {})", e.getSQLState());
        }
    }

    /** Maps a database error; marks the transaction unusable for everything except rollback. */
    private RuntimeException translate(SQLException e, Kind<?> kind, PgTx tx) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (tx != null) {
            tx.poisoned = true;
        }
        if (state.equals("40001") || state.equals("40P01")) {
            return new StoreException.Retryable(state);
        }
        if (state.equals("23505")) {
            String column = null;
            Kind<?> target = kind;
            if (e instanceof PSQLException pe) {
                ServerErrorMessage m = pe.getServerErrorMessage();
                if (m != null) {
                    if (target == null && m.getTable() != null) {
                        target = kindsByTable.get(m.getTable());
                    }
                    column = columnOf(target, m.getConstraint());
                }
            }
            StoreException.UniqueViolation v = new StoreException.UniqueViolation(target, column);
            if (tx != null && tx.violation == null) {
                tx.violation = v;
            }
            return v;
        }
        LOG.warn("storage error (SQLState {}, table {})", state, kind == null ? "-" : kind.table());
        return StoreException.unavailable();
    }

    /** Constraint names are {@code <table>_<column>_key} (PostgreSQL's default for column UNIQUE constraints). */
    private static String columnOf(Kind<?> kind, String constraint) {
        if (kind == null || constraint == null) {
            return null;
        }
        for (String column : kind.columns().keySet()) {
            if (constraint.equals(kind.table() + "_" + column + "_key")) {
                return column;
            }
        }
        return null;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private final class PgTx implements Tx {
        private final Connection connection;
        private final Map<String, Long> versions = new HashMap<>();
        private final List<Runnable> beforeCommit = new ArrayList<>();
        private final Map<String, Object> attachments = new HashMap<>();
        private boolean poisoned;
        private StoreException.UniqueViolation violation;

        PgTx(Connection connection) {
            this.connection = connection;
        }

        void runBeforeCommit() {
            for (int i = 0; i < beforeCommit.size(); i++) {
                beforeCommit.get(i).run();
            }
        }

        private void usable() {
            if (poisoned) {
                throw StoreException.unavailable();
            }
        }

        private String table(Kind<?> kind) {
            kindsByTable.putIfAbsent(kind.table(), kind);
            return SCHEMA + "." + kind.table();
        }

        private <A> List<A> query(Kind<A> kind, String sql, Object... params) {
            usable();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<A> result = new ArrayList<>();
                    while (rs.next()) {
                        UUID id = rs.getObject(1, UUID.class);
                        versions.put(kind.table() + ":" + id, rs.getLong(2));
                        result.add(JsonCodec.read(rs.getString(3), kind.type()));
                    }
                    return result;
                }
            } catch (SQLException e) {
                throw translate(e, kind, this);
            }
        }

        private int execute(Kind<?> kind, String sql, Object... params) {
            usable();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw translate(e, kind, this);
            }
        }

        private void bind(PreparedStatement ps, Object... params) throws SQLException {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof Instant instant) {
                    ps.setObject(i + 1, ts(instant));
                } else {
                    ps.setObject(i + 1, p);
                }
            }
        }

        private String select(Kind<?> kind) {
            return "SELECT id, version, payload::text FROM " + table(kind);
        }

        @Override
        public <A> Optional<A> get(Kind<A> kind, UUID id) {
            return id == null ? Optional.empty()
                    : query(kind, select(kind) + " WHERE id = ?", id).stream().findFirst();
        }

        @Override
        public <A> Optional<A> lockGet(Kind<A> kind, UUID id) {
            return id == null ? Optional.empty()
                    : query(kind, select(kind) + " WHERE id = ? FOR UPDATE", id).stream().findFirst();
        }

        @Override
        public <A> Optional<A> findOne(Kind<A> kind, String column, String value) {
            return value == null ? Optional.empty()
                    : query(kind, select(kind) + " WHERE " + kind.column(column) + " = ? ORDER BY seq LIMIT 1", value)
                            .stream().findFirst();
        }

        @Override
        public <A> Optional<A> lockFindOne(Kind<A> kind, String column, String value) {
            return value == null ? Optional.empty()
                    : query(kind, select(kind) + " WHERE " + kind.column(column)
                            + " = ? ORDER BY seq LIMIT 1 FOR UPDATE", value).stream().findFirst();
        }

        @Override
        public <A> List<A> find(Kind<A> kind, String column, String value) {
            return value == null ? List.of()
                    : query(kind, select(kind) + " WHERE " + kind.column(column) + " = ? ORDER BY seq", value);
        }

        @Override
        public long count(Kind<?> kind, String column, String value, Instant liveAt) {
            if (value == null) {
                return 0;
            }
            String sql = "SELECT count(*) FROM " + table(kind) + " WHERE " + kind.column(column) + " = ?"
                    + (liveAt == null ? "" : " AND (expires_at IS NULL OR expires_at > ?)");
            return scalar(kind, sql, liveAt == null ? new Object[] {value} : new Object[] {value, liveAt});
        }

        @Override
        public long countAll(Kind<?> kind) {
            return scalar(kind, "SELECT count(*) FROM " + table(kind));
        }

        @Override
        public long countLive(Kind<?> kind, Instant liveAt) {
            return scalar(kind, "SELECT count(*) FROM " + table(kind) + " WHERE expires_at IS NULL OR expires_at > ?",
                    liveAt);
        }

        private long scalar(Kind<?> kind, String sql, Object... params) {
            usable();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw translate(e, kind, this);
            }
        }

        @Override
        public <A> void insert(Kind<A> kind, A value) {
            UUID id = Objects.requireNonNull(kind.idOf(value), "id");
            Map<String, String> columns = kind.columnValues(value);
            StringBuilder names = new StringBuilder("id, tenant_id, version, payload, expires_at, created_at, updated_at");
            StringBuilder marks = new StringBuilder("?, ?, 1, ?::jsonb, ?, now(), now()");
            List<Object> params = new ArrayList<>(List.of(id));
            params.add(kind.tenantOf(value));
            params.add(JsonCodec.write(value));
            params.add(kind.expiresAtOf(value));
            columns.forEach((name, v) -> {
                names.append(", ").append(name);
                marks.append(", ?");
                params.add(v);
            });
            execute(kind, "INSERT INTO " + table(kind) + " (" + names + ") VALUES (" + marks + ")", params.toArray());
            versions.put(kind.table() + ":" + id, 1L);
        }

        @Override
        public <A> void update(Kind<A> kind, A value) {
            if (kind.immutable()) {
                throw new IllegalStateException("immutable record: " + kind.table());
            }
            UUID id = kind.idOf(value);
            String key = kind.table() + ":" + id;
            Long readVersion = versions.get(key);
            if (readVersion == null) {
                throw new IllegalStateException("update without read in this transaction: " + kind.table());
            }
            StringBuilder set = new StringBuilder(
                    "version = version + 1, tenant_id = ?, payload = ?::jsonb, expires_at = ?, updated_at = now()");
            List<Object> params = new ArrayList<>();
            params.add(kind.tenantOf(value));
            params.add(JsonCodec.write(value));
            params.add(kind.expiresAtOf(value));
            kind.columnValues(value).forEach((name, v) -> {
                set.append(", ").append(name).append(" = ?");
                params.add(v);
            });
            params.add(id);
            params.add(readVersion);
            int rows = execute(kind, "UPDATE " + table(kind) + " SET " + set + " WHERE id = ? AND version = ?",
                    params.toArray());
            if (rows != 1) {
                throw new StoreException.ConcurrentModification(kind.table());
            }
            versions.put(key, readVersion + 1);
        }

        @Override
        public void delete(Kind<?> kind, UUID id) {
            if (kind.immutable()) {
                throw new IllegalStateException("immutable record: " + kind.table());
            }
            execute(kind, "DELETE FROM " + table(kind) + " WHERE id = ?", id);
        }

        @Override
        public int deleteWhere(Kind<?> kind, String column, String value) {
            if (kind.immutable()) {
                throw new IllegalStateException("immutable record: " + kind.table());
            }
            return value == null ? 0
                    : execute(kind, "DELETE FROM " + table(kind) + " WHERE " + kind.column(column) + " = ?", value);
        }

        @Override
        public int deleteExpired(Kind<?> kind, Instant cutoff) {
            if (kind.immutable()) {
                throw new IllegalStateException("immutable record: " + kind.table());
            }
            return execute(kind, "DELETE FROM " + table(kind) + " WHERE expires_at <= ?", cutoff);
        }

        @Override
        public void lock(String key) {
            usable();
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, advisoryKey(key));
                ps.executeQuery().close();
            } catch (SQLException e) {
                throw translate(e, null, this);
            }
        }

        @Override
        public void putBlob(UUID id, String tenantId, byte[] content) {
            execute(null, "INSERT INTO " + SCHEMA + ".blobs (id, tenant_id, sha256, size_bytes, content, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, now())", id, tenantId, Hashing.sha256Hex(content),
                    (long) content.length, content);
        }

        @Override
        public Optional<byte[]> blob(UUID id, String tenantId) {
            usable();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT content FROM " + SCHEMA + ".blobs WHERE id = ? AND tenant_id = ?")) {
                ps.setObject(1, id);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw translate(e, null, this);
            }
        }

        @Override
        public void beforeCommit(Runnable action) {
            beforeCommit.add(action);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T attachment(String key, Supplier<T> factory) {
            return (T) attachments.computeIfAbsent(key, k -> factory.get());
        }
    }

    /** 64-bit advisory lock key from the SHA-256 of the logical key (collisions only over-serialize). */
    static long advisoryKey(String key) {
        byte[] digest = Hashing.sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xff);
        }
        return value;
    }
}
