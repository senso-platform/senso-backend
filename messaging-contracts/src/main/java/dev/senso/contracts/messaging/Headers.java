package dev.senso.contracts.messaging;

/** AMQP header names (AGENTS.md §6). */
public final class Headers {

    public static final String X_MESSAGE_ID = "x-message-id";
    public static final String X_GATEWAY_ID = "x-gateway-id";
    public static final String X_REJECT_REASON = "x-reject-reason";
    public static final String TRACEPARENT = "traceparent";

    private Headers() {}
}
