package dev.senso.contracts.messaging;

/** AMQP header names (AGENTS.md §6). */
public final class Headers {

    public static final String X_MESSAGE_ID = "x-message-id";
    public static final String X_GATEWAY_ID = "x-gateway-id";
    public static final String X_REJECT_REASON = "x-reject-reason";
    /** Короткое человекочитаемое пояснение к {@link #X_REJECT_REASON} (до 500 символов). */
    public static final String X_REJECT_DETAIL = "x-reject-detail";
    /** Исходный routing key сырого сообщения ({@code senso.v1.gw.<gatewayId>.<channel>}). */
    public static final String X_ORIGINAL_ROUTING_KEY = "x-original-routing-key";
    /** Момент приёма сообщения ingest-ом (ISO-8601 UTC). */
    public static final String X_RECEIVED_AT = "x-received-at";

    public static final String TRACEPARENT = "traceparent";

    private Headers() {}
}
