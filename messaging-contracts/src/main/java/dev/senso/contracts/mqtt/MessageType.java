package dev.senso.contracts.mqtt;

/** Message types published by gateways; the channel in the MQTT topic must match the type (ADR-0001 §6). */
public enum MessageType {
    TELEMETRY,
    DEVICE_DISCOVERED,
    DEVICE_INVENTORY,
    SECURITY_EVENT,
    GATEWAY_STATUS
}
