package dev.senso.ingest.validation;

/**
 * Причины отклонения сообщения на входе. {@link #name()} попадает в заголовок {@code x-reject-reason} и в тег метрики.
 * Порядок констант (кроме {@link #ROUTING_KEY}) — порядок проверок в {@link MessageValidator}.
 */
public enum RejectReason {
    /** Ключ не вида {@code senso.v1.gw.<gatewayId>.<channel>}; брокерный binding такого не пропускает, но ingest не верит. */
    ROUTING_KEY,
    PAYLOAD_TOO_LARGE,
    UNKNOWN_CHANNEL,
    MALFORMED_JSON,
    ENVELOPE_SCHEMA,
    GATEWAY_MISMATCH,
    CHANNEL_TYPE_MISMATCH,
    PAYLOAD_SCHEMA,
    TIMESTAMP_IN_FUTURE,
    SIGNATURE_INVALID
}
