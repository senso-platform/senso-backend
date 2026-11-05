package dev.senso.core.devices.api;

/** A metric supported by a device; unit is optional in the MQTT v1 contract. */
public record DeviceCapability(String metric, String unit) {}
