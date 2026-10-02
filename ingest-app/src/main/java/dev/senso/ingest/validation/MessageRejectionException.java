package dev.senso.ingest.validation;

/** Сообщения с такой причиной идут в {@code senso.dlq} с ключом {@code invalid}, исходное ack-ется. */
public class MessageRejectionException extends RuntimeException {

    private final RejectReason reason;

    public MessageRejectionException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
