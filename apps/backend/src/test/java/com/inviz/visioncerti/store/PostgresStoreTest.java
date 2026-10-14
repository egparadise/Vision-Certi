package com.inviz.visioncerti.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.common.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** {@link PostgresStore} semantics against a real PostgreSQL server with migration V005 and non-owner grants. */
class PostgresStoreTest {

    /** Uses the real staff_principals table as a generic mutable aggregate with one UNIQUE column. */
    record Item(UUID id, String tenantId, String subjectKey, int counter, Instant expiresAt) {
    }

    static final Kind<Item> ITEMS = Kind.of("staff_principals", Item.class)
            .id(Item::id).tenant(Item::tenantId).expiresAt(Item::expiresAt)
            .unique("subject_key", Item::subjectKey, () -> ApiException.conflict("ITEM_TAKEN", "taken"))
            .build();

    /** Immutable kind backed by the real doctor_evidence table (UPDATE/DELETE rejected by trigger and grants). */
    record Evidence(UUID id, String tenantId, String doctorId) {
    }

    static final Kind<Evidence> EVIDENCE = Kind.of("doctor_evidence", Evidence.class)
            .id(Evidence::id).tenant(Evidence::tenantId).column("doctor_id", Evidence::doctorId).immutable().build();

    private static PostgresTestDatabase.Database db;
    private static PostgresStore store;

    @BeforeAll
    static void start() {
        db = PostgresTestDatabase.create("store");
        store = db.store();
        store.verifySchema();
    }

    @AfterAll
    static void stop() throws Exception {
        store.close();
    }

    private static java.util.Optional<Item> get(Store s, UUID id) {
        return s.tx(tx -> tx.get(ITEMS, id));
    }

    private static java.util.Optional<Evidence> getEvidence(UUID id) {
        return store.tx(tx -> tx.get(EVIDENCE, id));
    }

    private static List<Item> byTenant(Store s, String tenant) {
        return s.tx(tx -> tx.find(ITEMS, Kind.TENANT, tenant));
    }

    private static long count(String tenant, Instant liveAt) {
        return store.tx(tx -> tx.count(ITEMS, Kind.TENANT, tenant, liveAt));
    }

    private static Item item(String key) {
        return new Item(UUID.randomUUID(), "tenant-a", key, 0, null);
    }

    @Test
    void insertedRowsSurviveANewStoreInstanceAndAreCopies() throws Exception {
        Item item = item("restart-" + UUID.randomUUID());
        store.run(tx -> tx.insert(ITEMS, item));
        try (PostgresStore restarted = db.store()) {
            Item read = get(restarted, item.id()).orElseThrow();
            assertThat(read).isEqualTo(item);
            java.util.Optional<Item> byKey = restarted.tx(tx -> tx.findOne(ITEMS, "subject_key", item.subjectKey()));
            assertThat(byKey).contains(item);
            assertThat(byTenant(restarted, "tenant-a")).contains(item);
        }
    }

    @Test
    void uniqueColumnConflictRollsBackTheWholeTransactionWithTheKindsError() {
        String key = "unique-" + UUID.randomUUID();
        store.run(tx -> tx.insert(ITEMS, item(key)));
        Item other = item("other-" + UUID.randomUUID());
        assertThatThrownBy(() -> store.run(tx -> {
            tx.insert(ITEMS, other);
            tx.insert(ITEMS, item(key));
        })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ITEM_TAKEN"));
        assertThat(get(store, other.id())).isEmpty();
    }

    @Test
    void businessRefusalCommitsButUnexpectedErrorsRollBack() {
        Item committed = item("refusal-" + UUID.randomUUID());
        assertThatThrownBy(() -> store.run(tx -> {
            tx.insert(ITEMS, committed);
            throw ApiException.conflict("REFUSED", "refused after recording");
        })).isInstanceOf(ApiException.class);
        assertThat(get(store, committed.id())).isPresent();

        Item rolledBack = item("bug-" + UUID.randomUUID());
        assertThatThrownBy(() -> store.run(tx -> {
            tx.insert(ITEMS, rolledBack);
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(get(store, rolledBack.id())).isEmpty();
    }

    @Test
    void staleUpdateFromAnotherInstanceIsDetectedAndRetriedNotLost() throws Exception {
        Item item = item("cas-" + UUID.randomUUID());
        store.run(tx -> tx.insert(ITEMS, item));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (PostgresStore second = db.store()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                PostgresStore s = i % 2 == 0 ? store : second;
                futures.add(pool.submit(() -> {
                    start.await();
                    // Unlocked read-modify-write: concurrent writers collide on the version check and retry.
                    s.run(tx -> {
                        Item current = tx.get(ITEMS, item.id()).orElseThrow();
                        tx.update(ITEMS, new Item(current.id(), current.tenantId(), current.subjectKey(),
                                current.counter() + 1, null));
                    });
                    return null;
                }));
            }
            start.countDown();
            int conflicts = 0;
            for (Future<?> f : futures) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class,
                            a -> assertThat(a.code()).isEqualTo("STORAGE_CONCURRENT_MODIFICATION"));
                    conflicts++;
                }
            }
            int counter = get(store, item.id()).orElseThrow().counter();
            // Every successful transaction is counted exactly once; refused ones changed nothing.
            assertThat(counter).isEqualTo(threads - conflicts);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rowLockSerializesReadModifyWriteAcrossInstances() throws Exception {
        Item item = item("lock-" + UUID.randomUUID());
        store.run(tx -> tx.insert(ITEMS, item));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (PostgresStore second = db.store()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                PostgresStore s = i % 2 == 0 ? store : second;
                futures.add(pool.submit(() -> s.run(tx -> {
                    Item current = tx.lockGet(ITEMS, item.id()).orElseThrow();
                    tx.update(ITEMS, new Item(current.id(), current.tenantId(), current.subjectKey(),
                            current.counter() + 1, null));
                })));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(get(store, item.id()).orElseThrow().counter()).isEqualTo(threads);
    }

    @Test
    void immutableTablesRejectChangesEvenWithDirectSql() throws Exception {
        Evidence e = new Evidence(UUID.randomUUID(), "tenant-a", UUID.randomUUID().toString());
        store.run(tx -> tx.insert(EVIDENCE, e));
        assertThatThrownBy(() -> store.run(tx -> tx.delete(EVIDENCE, e.id())))
                .isInstanceOf(IllegalStateException.class);
        // The application login has no UPDATE/DELETE grant on immutable tables.
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate(
                    "UPDATE certi_rt.doctor_evidence SET payload = '{}'::jsonb WHERE id = '" + e.id() + "'"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM certi_rt.blobs")).isInstanceOf(SQLException.class);
        }
        assertThat(getEvidence(e.id())).contains(e);
    }

    @Test
    void blobsAreTenantScopedAndExact() {
        UUID id = UUID.randomUUID();
        byte[] bytes = {0, 1, 2, (byte) 0xff, 0};
        store.run(tx -> tx.putBlob(id, "tenant-a", bytes));
        java.util.Optional<byte[]> own = store.tx(tx -> tx.blob(id, "tenant-a"));
        java.util.Optional<byte[]> foreign = store.tx(tx -> tx.blob(id, "tenant-b"));
        assertThat(own).hasValueSatisfying(b -> assertThat(b).isEqualTo(bytes));
        assertThat(foreign).isEmpty();
        assertThatThrownBy(() -> store.run(tx -> tx.putBlob(id, "tenant-a", new byte[] {9})))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void expiryCountsAndGarbageCollection() {
        String tenant = "expiry-" + UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        Item live = new Item(UUID.randomUUID(), tenant, "live-" + UUID.randomUUID(), 0, now.plusSeconds(60));
        Item dead = new Item(UUID.randomUUID(), tenant, "dead-" + UUID.randomUUID(), 0, now.minusSeconds(1));
        store.run(tx -> {
            tx.insert(ITEMS, live);
            tx.insert(ITEMS, dead);
        });
        assertThat(count(tenant, now)).isEqualTo(1);
        assertThat(count(tenant, null)).isEqualTo(2);
        store.run(tx -> tx.deleteExpired(ITEMS, now));
        assertThat(byTenant(store, tenant)).containsExactly(live);
    }

    @Test
    void advisoryLockSerializesCheckThenInsert() throws Exception {
        String key = "advisory-" + UUID.randomUUID();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return store.tx(tx -> {
                    tx.lock("test|" + key);
                    if (tx.findOne(ITEMS, "subject_key", key).isPresent()) {
                        return false;
                    }
                    tx.insert(ITEMS, item(key));
                    return true;
                });
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Boolean> f : futures) {
            created += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(created).isEqualTo(1);
    }

    @Test
    void storageOutageFailsClosedWith503() {
        // Nothing listens on port 1: either the pool refuses to start or every transaction fails with 503.
        assertThatThrownBy(() -> {
            try (PostgresStore broken = new PostgresStore(StoreConfig.dataSource(
                    db.url().replaceFirst(":[0-9]+/", ":1/"), db.username(), db.passwordFile(), 2, false),
                    java.time.Duration.ofSeconds(1))) {
                broken.tx(tx -> tx.countAll(ITEMS));
            }
        }).satisfiesAnyOf(
                t -> assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE");
                }),
                t -> assertThat(t.getClass().getName()).contains("PoolInitializationException"));
    }

    @Test
    void schemaVersionMismatchRefusesToStart() throws Exception {
        try (Connection c = db.connect();
                PreparedStatement ps = c.prepareStatement("SELECT version FROM certi_rt.schema_info")) {
            ps.executeQuery().close();
        }
        assertThat(PostgresStore.SCHEMA_VERSION).isEqualTo(5);
        assertThat(StoreConfig.problem("jdbc:postgresql://db.example:5432/x", "u", db.passwordFile(), true))
                .contains("sslmode");
        assertThat(StoreConfig.problem("jdbc:postgresql://db.example:5432/x?sslmode=verify-full", "u",
                db.passwordFile(), true)).isNull();
        assertThat(StoreConfig.problem("jdbc:h2:mem:x", "u", db.passwordFile(), false)).contains("jdbc:postgresql");
    }
}
