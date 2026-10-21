package dev.senso.ingest.outbound;

/**
 * Брокер не подтвердил публикацию (nack, таймаут confirm, сообщение вернулось как unroutable). Техническая ошибка:
 * листенер её пробрасывает, контейнер повторяет обработку 3 раза с паузой, затем отклоняет исходное сообщение без
 * requeue, и брокер уводит его в {@code q.dlq.failed}.
 */
public class PublishFailedException extends RuntimeException {

    public PublishFailedException(String message) {
        super(message);
    }

    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
