package com.inviz.visioncerti.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

    record Rec(UUID id, String name, Instant at, List<String> items) {
    }

    static final class Mutable {
        private final UUID id;
        private final List<Rec> recs = new ArrayList<>();
        private final transient byte[] secret;
        private final long size;
        int counter;

        Mutable(UUID id, byte[] secret) {
            this.id = id;
            this.secret = secret.clone();
            this.size = secret.length;
        }

        private Mutable() {
            this(null, new byte[0]);
        }
    }

    @Test
    void roundTrip() {
        Mutable m = new Mutable(UUID.randomUUID(), new byte[] {1, 2, 3});
        m.counter = 7;
        m.recs.add(new Rec(UUID.randomUUID(), "a", Instant.parse("2026-10-14T00:00:00Z"), List.of("x")));
        String json = JsonCodec.write(m);
        assertThat(json).doesNotContain("secret");
        Mutable back = JsonCodec.read(json, Mutable.class);
        assertThat(back.id).isEqualTo(m.id);
        assertThat(back.counter).isEqualTo(7);
        assertThat(back.size).isEqualTo(3);
        assertThat(back.recs).containsExactlyElementsOf(m.recs);
    }
}
