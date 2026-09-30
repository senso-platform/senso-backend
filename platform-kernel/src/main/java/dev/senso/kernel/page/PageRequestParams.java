package dev.senso.kernel.page;

import dev.senso.kernel.error.ValidationException;

/** Query parameters for cursor-paginated lists. */
public record PageRequestParams(int limit, String cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public PageRequestParams {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static PageRequestParams of(Integer limit, String cursor) {
        return new PageRequestParams(limit == null ? DEFAULT_LIMIT : limit, cursor);
    }

    public Cursor decodeCursor() {
        return cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
    }
}
