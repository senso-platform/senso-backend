from __future__ import annotations

import logging
import random
import time
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from uuid import uuid4

from uuid6 import uuid7

from senso_emulator.config import EmulatorConfig
from senso_emulator.messages import (
    OutboundMessage,
    build_device_discovered,
    build_device_inventory,
    build_gateway_status,
    build_security_event,
    build_telemetry,
)
from senso_emulator.mqtt_transport import MqttTransport
from senso_emulator.simulation import build_device_plan, generate_telemetry_samples

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class NormalResult:
    boot_id: str
    online: OutboundMessage
    inventory: OutboundMessage
    discovered: tuple[OutboundMessage, ...]
    telemetry: tuple[OutboundMessage, ...]
    offline: OutboundMessage

    @property
    def messages(self) -> tuple[OutboundMessage, ...]:
        return (self.online, self.inventory, *self.discovered, *self.telemetry, self.offline)


@dataclass(frozen=True)
class OfflineResult:
    boot_id: str
    online: OutboundMessage
    last_will: OutboundMessage


@dataclass(frozen=True)
class AnomalyResult:
    boot_id: str
    online: OutboundMessage
    event: OutboundMessage
    offline: OutboundMessage

    @property
    def messages(self) -> tuple[OutboundMessage, ...]:
        return (self.online, self.event, self.offline)


@dataclass(frozen=True)
class DiscoverResult:
    boot_id: str
    online: OutboundMessage
    inventory: OutboundMessage
    discovered: OutboundMessage
    offline: OutboundMessage

    @property
    def messages(self) -> tuple[OutboundMessage, ...]:
        return (self.online, self.inventory, self.discovered, self.offline)


@dataclass(frozen=True)
class DuplicateResult:
    boot_id: str
    online: OutboundMessage
    duplicate: OutboundMessage
    offline: OutboundMessage

    @property
    def messages(self) -> tuple[OutboundMessage, ...]:
        return (self.online, self.duplicate, self.duplicate, self.offline)


@dataclass(frozen=True)
class _ScenarioBody:
    messages: tuple[OutboundMessage, ...]
    next_sequence: int


@dataclass(frozen=True)
class _GracefulSessionResult:
    boot_id: str
    online: OutboundMessage
    body: tuple[OutboundMessage, ...]
    offline: OutboundMessage


def run_offline(
    config: EmulatorConfig,
    *,
    transport_factory: Callable[[EmulatorConfig, OutboundMessage], MqttTransport] | None = None,
    clock: Callable[[], datetime] | None = None,
) -> OfflineResult:
    transport_factory = transport_factory or MqttTransport
    clock = clock or _now
    boot_id = str(uuid4())
    last_will = build_gateway_status(
        gateway_id=config.gateway_id,
        boot_id=boot_id,
        state="OFFLINE",
        message_id=uuid7(),
        sent_at=clock(),
    )
    online = build_gateway_status(
        gateway_id=config.gateway_id,
        boot_id=boot_id,
        state="ONLINE",
        message_id=uuid7(),
        sent_at=clock(),
        seq=0,
    )

    transport = transport_factory(config, last_will)
    try:
        transport.connect()
        _publish(transport, online)
    except BaseException:
        transport.abort()
        raise

    cleanup_error = transport.abort()
    if cleanup_error is not None:
        raise cleanup_error
    return OfflineResult(boot_id=boot_id, online=online, last_will=last_will)


def run_anomaly(
    config: EmulatorConfig,
    *,
    transport_factory: Callable[[EmulatorConfig, OutboundMessage], MqttTransport] | None = None,
    clock: Callable[[], datetime] | None = None,
) -> AnomalyResult:
    clock = clock or _now

    def build_body(boot_id: str, sequence: int) -> _ScenarioBody:
        detected_at = clock()
        event = build_security_event(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            severity="WARNING",
            code="GATEWAY_TRAFFIC_SPIKE",
            message="Unexpected gateway traffic pattern detected",
            detected_at=detected_at,
            detector_name="senso-emulator",
            detector_version="0.1.0",
            details={"source": "anomaly-scenario"},
            message_id=uuid7(),
            sent_at=detected_at,
            seq=sequence,
        )
        return _ScenarioBody((event,), sequence + 1)

    session = _run_graceful_session(
        config,
        build_body,
        transport_factory=transport_factory,
        clock=clock,
    )
    return AnomalyResult(
        boot_id=session.boot_id,
        online=session.online,
        event=session.body[0],
        offline=session.offline,
    )


def run_discover(
    config: EmulatorConfig,
    *,
    transport_factory: Callable[[EmulatorConfig, OutboundMessage], MqttTransport] | None = None,
    clock: Callable[[], datetime] | None = None,
) -> DiscoverResult:
    clock = clock or _now
    device_plan = build_device_plan(config.device_count, 1)

    def build_body(boot_id: str, sequence: int) -> _ScenarioBody:
        inventory = build_device_inventory(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            devices=device_plan.inventory,
            message_id=uuid7(),
            sent_at=clock(),
            seq=sequence,
        )
        discovered = build_device_discovered(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            device=device_plan.discovered[0],
            message_id=uuid7(),
            sent_at=clock(),
            seq=sequence + 1,
        )
        return _ScenarioBody((inventory, discovered), sequence + 2)

    session = _run_graceful_session(
        config,
        build_body,
        initial_device_count=len(device_plan.inventory),
        final_device_count=len(device_plan.all_devices),
        transport_factory=transport_factory,
        clock=clock,
    )
    return DiscoverResult(
        boot_id=session.boot_id,
        online=session.online,
        inventory=session.body[0],
        discovered=session.body[1],
        offline=session.offline,
    )


def run_duplicate(
    config: EmulatorConfig,
    *,
    transport_factory: Callable[[EmulatorConfig, OutboundMessage], MqttTransport] | None = None,
    clock: Callable[[], datetime] | None = None,
) -> DuplicateResult:
    clock = clock or _now

    def build_body(boot_id: str, sequence: int) -> _ScenarioBody:
        detected_at = clock()
        event = build_security_event(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            severity="INFO",
            code="DUPLICATE_TEST_EVENT",
            message="Intentional duplicate delivery test",
            detected_at=detected_at,
            detector_name="senso-emulator",
            detector_version="0.1.0",
            details={"source": "duplicate-scenario"},
            message_id=uuid7(),
            sent_at=detected_at,
            seq=sequence,
        )
        return _ScenarioBody((event, event), sequence + 1)

    session = _run_graceful_session(
        config,
        build_body,
        transport_factory=transport_factory,
        clock=clock,
    )
    return DuplicateResult(
        boot_id=session.boot_id,
        online=session.online,
        duplicate=session.body[0],
        offline=session.offline,
    )


def run_normal(
    config: EmulatorConfig,
    *,
    transport_factory: Callable[[EmulatorConfig, OutboundMessage], MqttTransport] | None = None,
    clock: Callable[[], datetime] | None = None,
    sleeper: Callable[[float], None] | None = None,
) -> NormalResult:
    transport_factory = transport_factory or MqttTransport
    clock = clock or _now
    sleeper = sleeper or time.sleep
    boot_id = str(uuid4())
    device_plan = build_device_plan(config.device_count, config.discovered_device_count)
    random_source = random.Random(config.seed)
    last_will = build_gateway_status(
        gateway_id=config.gateway_id,
        boot_id=boot_id,
        state="OFFLINE",
        message_id=uuid7(),
        sent_at=clock(),
    )

    transport = transport_factory(config, last_will)
    sequence = 0
    discovered_messages: list[OutboundMessage] = []
    telemetry_messages: list[OutboundMessage] = []
    try:
        transport.connect()
        online = build_gateway_status(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            state="ONLINE",
            message_id=uuid7(),
            sent_at=clock(),
            seq=sequence,
            device_count=len(device_plan.inventory),
        )
        _publish(transport, online)
        sequence += 1

        inventory = build_device_inventory(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            devices=device_plan.inventory,
            message_id=uuid7(),
            sent_at=clock(),
            seq=sequence,
        )
        _publish(transport, inventory)
        sequence += 1

        for device in device_plan.discovered:
            discovered = build_device_discovered(
                gateway_id=config.gateway_id,
                boot_id=boot_id,
                device=device,
                message_id=uuid7(),
                sent_at=clock(),
                seq=sequence,
            )
            _publish(transport, discovered)
            discovered_messages.append(discovered)
            sequence += 1

        for batch in range(config.telemetry_batches):
            if batch:
                sleeper(config.telemetry_interval)
            sampled_at = clock()
            telemetry = build_telemetry(
                gateway_id=config.gateway_id,
                boot_id=boot_id,
                samples=generate_telemetry_samples(
                    device_plan.all_devices,
                    sampled_at,
                    random_source,
                ),
                message_id=uuid7(),
                sent_at=sampled_at,
                seq=sequence,
            )
            _publish(transport, telemetry)
            telemetry_messages.append(telemetry)
            sequence += 1

        offline = build_gateway_status(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            state="OFFLINE",
            message_id=uuid7(),
            sent_at=clock(),
            seq=sequence,
            device_count=len(device_plan.all_devices),
        )
        _publish(transport, offline)
    except BaseException:
        transport.abort()
        raise

    transport.disconnect_gracefully()
    return NormalResult(
        boot_id=boot_id,
        online=online,
        inventory=inventory,
        discovered=tuple(discovered_messages),
        telemetry=tuple(telemetry_messages),
        offline=offline,
    )


def run_normal_once(config: EmulatorConfig) -> NormalResult:
    return run_normal(replace(config, once=True))


def _run_graceful_session(
    config: EmulatorConfig,
    body_builder: Callable[[str, int], _ScenarioBody],
    *,
    initial_device_count: int | None = None,
    final_device_count: int | None = None,
    transport_factory: Callable[[EmulatorConfig, OutboundMessage], MqttTransport] | None = None,
    clock: Callable[[], datetime],
) -> _GracefulSessionResult:
    transport_factory = transport_factory or MqttTransport
    boot_id = str(uuid4())
    last_will = build_gateway_status(
        gateway_id=config.gateway_id,
        boot_id=boot_id,
        state="OFFLINE",
        message_id=uuid7(),
        sent_at=clock(),
    )
    transport = transport_factory(config, last_will)
    try:
        transport.connect()
        online = build_gateway_status(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            state="ONLINE",
            message_id=uuid7(),
            sent_at=clock(),
            seq=0,
            device_count=initial_device_count,
        )
        _publish(transport, online)

        body = body_builder(boot_id, 1)
        for message in body.messages:
            _publish(transport, message)

        offline = build_gateway_status(
            gateway_id=config.gateway_id,
            boot_id=boot_id,
            state="OFFLINE",
            message_id=uuid7(),
            sent_at=clock(),
            seq=body.next_sequence,
            device_count=final_device_count,
        )
        _publish(transport, offline)
    except BaseException:
        transport.abort()
        raise

    transport.disconnect_gracefully()
    return _GracefulSessionResult(
        boot_id=boot_id,
        online=online,
        body=body.messages,
        offline=offline,
    )


def _publish(transport: MqttTransport, message: OutboundMessage) -> None:
    message_id = message.document["messageId"]
    message_type = message.document["type"]
    mid = transport.publish(message)
    LOGGER.info("PUBACK received (type=%s, messageId=%s, mid=%s)", message_type, message_id, mid)


def _now() -> datetime:
    return datetime.now(UTC)
