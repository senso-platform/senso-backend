package dev.senso.core.devices.internal.service;

import dev.senso.core.devices.api.GatewayView;

/** Creation result: the raw MQTT password is returned once and never persisted. */
public record RegisteredGateway(GatewayView gateway, String mqttPassword) {

    @Override
    public String toString() {
        return "RegisteredGateway[gateway=" + gateway + ", mqttPassword=<redacted>]";
    }
}
