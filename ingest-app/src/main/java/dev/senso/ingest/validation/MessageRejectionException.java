package dev.senso.ingest.validation;

import dev.senso.contracts.mqtt.MessageType;
import org.jspecify.annotations.Nullable;

/**
 * Сообщение нарушает контракт. Такие идут в {@code senso.dlq} с ключом {@code invalid}, исходное ack-ется.
 * {@link #getMessage()} — короткая причина для заголовка {@code x-reject-detail}, не длиннее {@value #MAX_DETAIL}
 * символов и без содержимого сообщения целиком. {@link #messageId()} и {@link #type()} заполнены, если валидатор
 * успел их прочитать (для лога, метрики и заголовков DLQ).
 */
public class MessageRejectionException extends RuntimeException {

    static final int MAX_DETAIL = 500;

    private final RejectReason reason;
    private final @Nullable String messageId;
    private final @Nullable MessageType type;

    public MessageRejectionException(RejectReason reason, String detail) {
        this(reason, detail, null, null);
    }

    private MessageRejectionException(
            RejectReason reason, String detail, @Nullable String messageId, @Nullable MessageType type) {
        super(truncate(detail), null, false, false); // ожидаемый исход, стек не нужен
        this.reason = reason;
        this.messageId = messageId;
        this.type = type;
    }

    /** Та же причина с тем, что удалось прочитать из сообщения. */
    MessageRejectionException withContext(@Nullable String messageId, @Nullable MessageType type) {
        return new MessageRejectionException(reason, getMessage(), messageId, type);
    }

    public RejectReason reason() {
        return reason;
    }

    public @Nullable String messageId() {
        return messageId;
    }

    public @Nullable MessageType type() {
        return type;
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL - 1) + "…";
    }
}
