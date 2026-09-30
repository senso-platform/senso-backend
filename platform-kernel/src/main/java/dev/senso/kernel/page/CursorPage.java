package dev.senso.kernel.page;

import java.util.List;
import java.util.function.Function;

/** Page result with an opaque forward-only cursor (ADR-0001 §9.1). */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), null);
    }

    public static <T> CursorPage<T> of(List<T> items, int limit, Function<T, Cursor> cursorOf) {
        if (items.size() < limit) {
            return new CursorPage<>(items, null);
        }
        return new CursorPage<>(
                items, cursorOf.apply(items.get(items.size() - 1)).encode());
    }
}
