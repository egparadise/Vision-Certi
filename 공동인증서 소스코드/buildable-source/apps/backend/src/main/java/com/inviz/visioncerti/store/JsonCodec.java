package com.inviz.visioncerti.store;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Field-based JSON for stored aggregates: private fields (including final ones) are written and restored as-is,
 * without getters or setters, so domain classes need no persistence annotations. Classes are instantiated through a
 * private no-argument constructor (records through their canonical constructor); other constructors are never used,
 * so validation or defensive copying in them cannot interfere with restoring stored state.
 */
public final class JsonCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultVisibility(v -> v
                    .withFieldVisibility(Visibility.ANY)
                    .withGetterVisibility(Visibility.NONE)
                    .withIsGetterVisibility(Visibility.NONE)
                    .withSetterVisibility(Visibility.NONE)
                    .withCreatorVisibility(Visibility.NONE))
            .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonCodec() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <A> A read(String json, Class<A> type) {
        return MAPPER.readValue(json, type);
    }
}
