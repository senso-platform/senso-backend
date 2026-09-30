package dev.senso.kernel.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.senso.kernel.error.ValidationException;
import java.time.Instant;
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
}
