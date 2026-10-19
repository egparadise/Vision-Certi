package com.inviz.visioncerti.common;

import java.util.List;

/** Uniform list envelope: {@code {items:[...],nextCursor}}. */
public record ListResponse<T>(List<T> items, String nextCursor) {

    /** Single page; cursor pagination is not implemented in this slice so {@code nextCursor} is null. */
    public static <T> ListResponse<T> of(List<T> items) {
        return new ListResponse<>(List.copyOf(items), null);
    }
}
