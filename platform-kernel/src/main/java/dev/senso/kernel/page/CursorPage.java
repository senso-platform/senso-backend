package dev.senso.kernel.page;

import java.util.List;
import java.util.function.Function;

/**
 * Page result with an opaque forward-only cursor (ADR-0001 §9.1).
 *
 * <p>Как пользоваться (keyset-пагинация):
 *
 * <pre>{@code
 * List<AlertRow> rows = repository.findPage(ownerId, params.decodeCursor(), params.fetchSize()); // LIMIT limit + 1
 * return CursorPage.of(rows, params.limit(), row -> new Cursor(row.lastOccurredAt(), row.id()));
 * }</pre>
 *
 * Запрос читает на одну строку больше лимита: так без лишнего запроса известно, есть ли следующая страница.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), null);
    }

    /**
     * @param fetched результат запроса с {@code LIMIT limit + 1} ({@link PageRequestParams#fetchSize()})
     * @param limit размер страницы, который увидит клиент
     * @param cursorOf ключ сортировки строки — тот же, что в {@code ORDER BY}
     */
    public static <T> CursorPage<T> of(List<T> fetched, int limit, Function<T, Cursor> cursorOf) {
        if (fetched.size() <= limit) {
            return new CursorPage<>(fetched, null);
        }
        List<T> page = fetched.subList(0, limit);
        return new CursorPage<>(page, cursorOf.apply(page.get(page.size() - 1)).encode());
    }
}
