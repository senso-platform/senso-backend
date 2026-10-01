package dev.senso.kernel.page;

import dev.senso.kernel.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyset cursor: base64url-encoded JSON {@code {"us":<epochMicros>,"id":"<uuid>"}}.
 * The pair (timestamp, id) matches the sort order of list queries (AGENTS.md §5).
 *
 * <p>Точность — микросекунды, как у PostgreSQL {@code timestamptz}. С миллисекундами курсор «округлял» время
 * последней записи страницы, и записи с тем же временем, но меньшим id, пропускались на следующей странице.
 * Наносекунды (если {@code Instant} не из БД) отбрасываются.
 */
public record Cursor(Instant timestamp, UUID id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Pattern WIRE_FORMAT =
            Pattern.compile("^\\{\"us\":(\\d{1,19}),\"id\":\"([0-9a-fA-F-]{36})\"\\}$");

    public Cursor {
        if (timestamp == null || id == null) {
            throw new IllegalArgumentException("cursor parts must not be null");
        }
        timestamp = timestamp.truncatedTo(ChronoUnit.MICROS);
    }

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, timestamp);
        String json = "{\"us\":" + micros + ",\"id\":\"" + id + "\"}";
        return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("cursor must not be empty");
        }
        String json;
        try {
            json = new String(DECODER.decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("cursor is not valid base64url", e);
        }
        Matcher m = WIRE_FORMAT.matcher(json);
        if (!m.matches()) {
            throw new ValidationException("cursor has unexpected format");
        }
        try {
            Instant timestamp = Instant.EPOCH.plus(Long.parseLong(m.group(1)), ChronoUnit.MICROS);
            return new Cursor(timestamp, UUID.fromString(m.group(2)));
        } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException e) {
            throw new ValidationException("cursor has invalid payload", e);
        }
    }
}
