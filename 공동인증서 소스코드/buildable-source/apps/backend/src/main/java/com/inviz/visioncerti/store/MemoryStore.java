package com.inviz.visioncerti.store;

import com.inviz.visioncerti.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Process-local {@link Store} for the {@code dev} profile and tests. It mirrors the PostgreSQL semantics that the
 * services depend on - JSON copies (a change is visible only after insert/update), unique columns, version checks,
 * rollback on failure, commit on business refusal - but transactions are serialized by one lock and nothing
 * survives a restart. Never used in {@code prod}.
 */
public final class MemoryStore implements Store {

    private record Row(long version, String tenant, Instant expiresAt, Map<String, String> columns, String json) {
    }

    private record BlobRow(String tenant, byte[] content) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, LinkedHashMap<UUID, Row>> tables = new HashMap<>();
    private final Map<UUID, BlobRow> blobs = new HashMap<>();
    private final ThreadLocal<MemTx> current = new ThreadLocal<>();

    @Override
    public String mode() {
        return MODE_MEMORY;
    }

    @Override
    public boolean inTransaction() {
        return current.get() != null;
    }

    @Override
    public <T> T tx(Function<Tx, T> work) {
        MemTx existing = current.get();
        if (existing != null) {
            return work.apply(existing);
        }
        lock.lock();
        MemTx tx = new MemTx();
        current.set(tx);
        try {
            T result = null;
            ApiException refusal = null;
            try {
                result = work.apply(tx);
            } catch (ApiException e) {
                refusal = e;
            } catch (StoreException.UniqueViolation e) {
                tx.rollback();
                throw e.toApi();
            } catch (RuntimeException | Error e) {
                tx.rollback();
                throw e;
            }
            try {
                tx.runBeforeCommit();
            } catch (StoreException.UniqueViolation e) {
                tx.rollback();
                throw e.toApi();
            } catch (RuntimeException | Error e) {
                tx.rollback();
                throw e;
            }
            if (tx.violation != null) {
                tx.rollback();
                throw tx.violation.toApi();
            }
            if (refusal != null) {
                throw refusal;
            }
            return result;
        } finally {
            current.remove();
            lock.unlock();
        }
    }

    private LinkedHashMap<UUID, Row> table(Kind<?> kind) {
        return tables.computeIfAbsent(kind.table(), t -> new LinkedHashMap<>());
    }

    private final class MemTx implements Tx {
        private final List<Runnable> undo = new ArrayList<>();
        private final Map<String, Long> versions = new HashMap<>();
        private final List<Runnable> beforeCommit = new ArrayList<>();
        private final Map<String, Object> attachments = new HashMap<>();
        private StoreException.UniqueViolation violation;

        void rollback() {
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
            undo.clear();
        }

        void runBeforeCommit() {
            for (int i = 0; i < beforeCommit.size(); i++) {
                beforeCommit.get(i).run();
            }
        }

        private <A> A decode(Kind<A> kind, UUID id, Row row) {
            versions.put(kind.table() + ":" + id, row.version());
            return JsonCodec.read(row.json(), kind.type());
        }

        private static String valueOf(Row row, String column) {
            return Kind.TENANT.equals(column) ? row.tenant() : row.columns().get(column);
        }

        @Override
        public <A> Optional<A> get(Kind<A> kind, UUID id) {
            Row row = id == null ? null : table(kind).get(id);
            return row == null ? Optional.empty() : Optional.of(decode(kind, id, row));
        }

        @Override
        public <A> Optional<A> lockGet(Kind<A> kind, UUID id) {
            return get(kind, id);
        }

        @Override
        public <A> Optional<A> findOne(Kind<A> kind, String column, String value) {
            kind.column(column);
            if (value == null) {
                return Optional.empty();
            }
            for (Map.Entry<UUID, Row> e : table(kind).entrySet()) {
                if (value.equals(valueOf(e.getValue(), column))) {
                    return Optional.of(decode(kind, e.getKey(), e.getValue()));
                }
            }
            return Optional.empty();
        }

        @Override
        public <A> Optional<A> lockFindOne(Kind<A> kind, String column, String value) {
            return findOne(kind, column, value);
        }

        @Override
        public <A> List<A> find(Kind<A> kind, String column, String value) {
            kind.column(column);
            List<A> result = new ArrayList<>();
            if (value == null) {
                return result;
            }
            for (Map.Entry<UUID, Row> e : table(kind).entrySet()) {
                if (value.equals(valueOf(e.getValue(), column))) {
                    result.add(decode(kind, e.getKey(), e.getValue()));
                }
            }
            return result;
        }

        @Override
        public long count(Kind<?> kind, String column, String value, Instant liveAt) {
            kind.column(column);
            return table(kind).values().stream()
                    .filter(r -> value != null && value.equals(valueOf(r, column)))
                    .filter(r -> liveAt == null || r.expiresAt() == null || r.expiresAt().isAfter(liveAt))
                    .count();
        }

        @Override
        public long countAll(Kind<?> kind) {
            return table(kind).size();
        }

        @Override
        public long countLive(Kind<?> kind, Instant liveAt) {
            return table(kind).values().stream()
                    .filter(r -> r.expiresAt() == null || r.expiresAt().isAfter(liveAt))
                    .count();
        }

        @Override
        public <A> void insert(Kind<A> kind, A value) {
            UUID id = Objects.requireNonNull(kind.idOf(value), "id");
            LinkedHashMap<UUID, Row> rows = table(kind);
            if (rows.containsKey(id)) {
                throw violation(new StoreException.UniqueViolation(kind, "id"));
            }
            Row row = row(kind, value, 1);
            checkUnique(kind, id, row);
            rows.put(id, row);
            versions.put(kind.table() + ":" + id, 1L);
            undo.add(() -> rows.remove(id));
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
            LinkedHashMap<UUID, Row> rows = table(kind);
            Row previous = rows.get(id);
            if (previous == null || previous.version() != readVersion) {
                throw new StoreException.ConcurrentModification(kind.table());
            }
            Row row = row(kind, value, readVersion + 1);
            checkUnique(kind, id, row);
            rows.put(id, row);
            versions.put(key, readVersion + 1);
            undo.add(() -> rows.put(id, previous));
        }

        @Override
        public void delete(Kind<?> kind, UUID id) {
            if (kind.immutable()) {
                throw new IllegalStateException("immutable record: " + kind.table());
            }
            LinkedHashMap<UUID, Row> rows = table(kind);
            Row previous = rows.remove(id);
            if (previous != null) {
                undo.add(() -> rows.put(id, previous));
            }
        }

        @Override
        public int deleteWhere(Kind<?> kind, String column, String value) {
            kind.column(column);
            List<UUID> ids = new ArrayList<>();
            table(kind).forEach((id, row) -> {
                if (value != null && value.equals(valueOf(row, column))) {
                    ids.add(id);
                }
            });
            ids.forEach(id -> delete(kind, id));
            return ids.size();
        }

        @Override
        public int deleteExpired(Kind<?> kind, Instant cutoff) {
            List<UUID> ids = new ArrayList<>();
            table(kind).forEach((id, row) -> {
                if (row.expiresAt() != null && !row.expiresAt().isAfter(cutoff)) {
                    ids.add(id);
                }
            });
            ids.forEach(id -> delete(kind, id));
            return ids.size();
        }

        @Override
        public void lock(String key) {
            // Transactions are already serialized by the store lock.
        }

        @Override
        public void putBlob(UUID id, String tenantId, byte[] content) {
            if (blobs.containsKey(id)) {
                throw violation(new StoreException.UniqueViolation(null, "id"));
            }
            blobs.put(id, new BlobRow(tenantId, content.clone()));
            undo.add(() -> blobs.remove(id));
        }

        @Override
        public Optional<byte[]> blob(UUID id, String tenantId) {
            BlobRow row = blobs.get(id);
            return row == null || !row.tenant().equals(tenantId) ? Optional.empty()
                    : Optional.of(row.content().clone());
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

        private <A> Row row(Kind<A> kind, A value, long version) {
            return new Row(version, kind.tenantOf(value), kind.expiresAtOf(value), kind.columnValues(value),
                    JsonCodec.write(value));
        }

        private void checkUnique(Kind<?> kind, UUID id, Row row) {
            row.columns().forEach((column, v) -> {
                if (v == null || !kind.isUnique(column)) {
                    return;
                }
                for (Map.Entry<UUID, Row> e : table(kind).entrySet()) {
                    if (!e.getKey().equals(id) && v.equals(e.getValue().columns().get(column))) {
                        throw violation(new StoreException.UniqueViolation(kind, column));
                    }
                }
            });
        }

        private StoreException.UniqueViolation violation(StoreException.UniqueViolation v) {
            // Mirrors PostgreSQL: after a constraint violation the transaction can only roll back.
            if (violation == null) {
                violation = v;
            }
            return v;
        }
    }
}
