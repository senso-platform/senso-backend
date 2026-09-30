package dev.senso.contracts.messaging;

/** Exchange names. Never inline these strings in code (AGENTS.md §6). */
public final class Exchanges {

    public static final String AMQ_TOPIC = "amq.topic";
    public static final String VALIDATED = "senso.validated";
    public static final String NOTIFICATIONS = "senso.notifications";
    public static final String LIVE = "senso.live";
    public static final String DLQ = "senso.dlq";

    private Exchanges() {}
}
