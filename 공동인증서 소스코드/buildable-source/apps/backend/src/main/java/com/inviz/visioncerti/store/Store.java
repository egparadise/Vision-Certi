package com.inviz.visioncerti.store;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Transactional aggregate storage. {@link #tx} joins a transaction already open on this thread.
 *
 * <p>Outcome of the outermost transaction: normal return or a business refusal ({@link
 * com.inviz.visioncerti.common.ApiException}) commits - refusals often record durable facts first (consumed
 * challenge, failure counter, denial audit) exactly like the in-memory services did; any other exception rolls back.
 * Storage failures surface as 503 {@code STORAGE_UNAVAILABLE}; a uniqueness conflict rolls back and surfaces as the
 * {@link Kind}'s conflict error.
 */
public interface Store {

    String MODE_MEMORY = "IN_MEMORY";
    String MODE_POSTGRESQL = "POSTGRESQL";

    <T> T tx(Function<Tx, T> work);

    default void run(Consumer<Tx> work) {
        tx(t -> {
            work.accept(t);
            return null;
        });
    }

    /** {@link #MODE_MEMORY} or {@link #MODE_POSTGRESQL}. */
    String mode();

    /** True while this thread is inside a transaction (used to assert no network I/O inside one). */
    boolean inTransaction();
}
