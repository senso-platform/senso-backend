package dev.senso.ingest.validation;

import java.util.Locale;

/** Причины отклонения сообщения на входе. Попадают в заголовок {@code x-reject-reason} и в метрику. */
public enum RejectReason {
    ROUTING_KEY,
    TOO_LARGE,
    MALFORMED_JSON,
    ENVELOPE_SCHEMA,
    PAYLOAD_SCHEMA,
    CHANNEL_TYPE,
    GATEWAY_MISMATCH,
    FUTURE_TIMESTAMP,
    SIGNATURE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
