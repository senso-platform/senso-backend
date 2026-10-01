package dev.senso.kernel.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.senso.kernel.error.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void encodesAndDecodesBack() {
        Cursor original = new Cursor(Instant.ofEpochMilli(1_759_000_000_123L), UUID.randomUUID());
        Cursor decoded = Cursor.decode(original.encode());
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void encodedFormIsBase64UrlWithoutPadding() {
        String encoded = new Cursor(Instant.ofEpochMilli(1_759_000_000_000L), new UUID(0L, 1L)).encode();
        assertThat(encoded).doesNotContain("=", "+", "/");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Cursor.decode("###not-base64###")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Cursor.decode(java.util.Base64.getUrlEncoder().encodeToString("{\"x\":1}".getBytes())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unexpected format");
        assertThatThrownBy(() -> Cursor.decode("")).isInstanceOf(ValidationException.class);
    }

    @Test
    void pageRequestClampsAndDecodes() {
        assertThat(PageRequestParams.of(null, null).limit()).isEqualTo(PageRequestParams.DEFAULT_LIMIT);
        assertThatThrownBy(() -> PageRequestParams.of(101, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PageRequestParams.of(0, null)).isInstanceOf(ValidationException.class);

        Cursor cursor = new Cursor(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), UUID.randomUUID());
        PageRequestParams params = new PageRequestParams(10, cursor.encode());
        assertThat(params.decodeCursor()).isEqualTo(cursor);
        assertThat(PageRequestParams.of(10, null).decodeCursor()).isNull();
    }

    @Test
    void keepsMicrosecondsLikeTimestamptz() {
        // 2026-09-25T10:15:30.123456Z: две записи в одну миллисекунду не должны «склеиться» в курсоре
        Instant micros = Instant.parse("2026-09-25T10:15:30.123456Z");
        Cursor decoded = Cursor.decode(new Cursor(micros, new UUID(0L, 7L)).encode());
        assertThat(decoded.timestamp()).isEqualTo(micros);
    }

    @Test
    void dropsNanosecondsBeyondDatabasePrecision() {
        Cursor cursor = new Cursor(Instant.parse("2026-09-25T10:15:30.123456789Z"), new UUID(0L, 7L));
        assertThat(cursor.timestamp()).isEqualTo(Instant.parse("2026-09-25T10:15:30.123456Z"));
    }

    @Test
    void rejectsTimestampOutOfRange() {
        String json = "{\"us\":9999999999999999999,\"id\":\"" + new UUID(0L, 1L) + "\"}";
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        assertThatThrownBy(() -> Cursor.decode(encoded)).isInstanceOf(ValidationException.class);
    }

    @Test
    void pageHasNextCursorOnlyWhenMoreRowsWereFetched() {
        Instant t = Instant.parse("2026-09-25T10:15:30Z");
        List<Cursor> threeRows = List.of(
                new Cursor(t.plusSeconds(3), new UUID(0L, 3L)),
                new Cursor(t.plusSeconds(2), new UUID(0L, 2L)),
                new Cursor(t.plusSeconds(1), new UUID(0L, 1L)));

        // limit 2, из БД пришло 3 (= fetchSize) → страница из 2, курсор на последнюю показанную
        CursorPage<Cursor> first = CursorPage.of(threeRows, 2, row -> row);
        assertThat(first.items()).containsExactly(threeRows.get(0), threeRows.get(1));
        assertThat(Cursor.decode(first.nextCursor())).isEqualTo(threeRows.get(1));

        // ровно limit строк → следующей страницы нет (раньше здесь отдавался лишний курсор)
        CursorPage<Cursor> last = CursorPage.of(threeRows.subList(0, 2), 2, row -> row);
        assertThat(last.items()).hasSize(2);
        assertThat(last.nextCursor()).isNull();

        assertThat(PageRequestParams.of(2, null).fetchSize()).isEqualTo(3);
    }
}
