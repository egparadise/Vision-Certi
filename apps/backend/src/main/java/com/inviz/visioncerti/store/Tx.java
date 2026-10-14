package com.inviz.visioncerti.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One storage transaction. Every value returned is a private copy: changes become durable only through
 * {@link #insert} / {@link #update}. {@link #update} requires that the aggregate was read in this transaction and
 * succeeds only if nobody changed it since (optimistic version check); {@code lock*} reads additionally take a row
 * lock until commit. No network I/O may happen while a transaction is open.
 */
public interface Tx {

    <A> Optional<A> get(Kind<A> kind, UUID id);

    /** Reads and row-locks ({@code SELECT … FOR UPDATE}, bounded by the lock timeout). */
    <A> Optional<A> lockGet(Kind<A> kind, UUID id);

    /** First row (insertion order) whose {@code column} equals {@code value}. */
    <A> Optional<A> findOne(Kind<A> kind, String column, String value);

    <A> Optional<A> lockFindOne(Kind<A> kind, String column, String value);

    /** All rows whose {@code column} equals {@code value}, in insertion order. */
    <A> List<A> find(Kind<A> kind, String column, String value);

    /** Rows matching {@code column = value} that are not expired at {@code liveAt} (null = count all). */
    long count(Kind<?> kind, String column, String value, Instant liveAt);

    long countAll(Kind<?> kind);

    /** Rows not expired at {@code liveAt}. */
    long countLive(Kind<?> kind, Instant liveAt);

    <A> void insert(Kind<A> kind, A value);

    <A> void update(Kind<A> kind, A value);

    void delete(Kind<?> kind, UUID id);

    int deleteWhere(Kind<?> kind, String column, String value);

    /** Deletes rows whose expiry is at or before {@code cutoff}; returns the number removed. */
    int deleteExpired(Kind<?> kind, Instant cutoff);

    /** Transaction-scoped exclusive lock on an arbitrary key (PostgreSQL advisory lock). */
    void lock(String key);

    /** Stores immutable bytes under {@code id}. */
    void putBlob(UUID id, String tenantId, byte[] content);

    Optional<byte[]> blob(UUID id, String tenantId);

    /** Runs {@code action} inside this transaction right before commit (after all other work). */
    void beforeCommit(Runnable action);

    /** Per-transaction attachment, created on first use. */
    <T> T attachment(String key, Supplier<T> factory);
}
