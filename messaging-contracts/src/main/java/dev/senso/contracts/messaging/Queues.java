package dev.senso.contracts.messaging;

/** Queue names. Never inline these strings in code (AGENTS.md §6). */
public final class Queues {

    public static final String INGEST_RAW = "q.ingest.raw";
    public static final String TELEMETRY_WRITER = "q.telemetry.writer";
    public static final String DEVICES_INBOUND = "q.devices.inbound";
    public static final String ALERTS_INBOUND = "q.alerts.inbound";
    public static final String DLQ_INVALID = "q.dlq.invalid";
    public static final String DLQ_FAILED = "q.dlq.failed";

    private Queues() {}
}
