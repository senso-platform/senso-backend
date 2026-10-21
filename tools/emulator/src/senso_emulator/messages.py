from __future__ import annotations

import json
import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

GATEWAY_ID_PATTERN = re.compile(r"^[a-z0-9-]{3,64}$")
BOOT_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
DEVICE_ID_PATTERN = re.compile(r"^[a-z0-9-]{1,64}$")
DEVICE_TYPE_PATTERN = re.compile(r"^[A-Z0-9_]{2,32}$")
METRIC_PATTERN = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
SECURITY_CODE_PATTERN = re.compile(r"^[A-Z0-9_]{3,64}$")
GATEWAY_STATES = frozenset({"ONLINE", "DEGRADED", "OFFLINE"})
SECURITY_SEVERITIES = frozenset({"INFO", "WARNING", "CRITICAL"})


@dataclass(frozen=True)
class Capability:
    metric: str
    unit: str | None = None


@dataclass(frozen=True)
class Device:
    device_id: str
    device_type: str
    capabilities: tuple[Capability, ...] = ()
    metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class TelemetrySample:
    device_id: str
    sampled_at: datetime
    metrics: Mapping[str, float]


@dataclass(frozen=True)
class OutboundMessage:
    topic: str
    document: dict[str, Any]
    payload: bytes
    qos: int = 1
    retain: bool = False


def build_gateway_status(
    *,
    gateway_id: str,
    boot_id: str,
    state: str,
    message_id: UUID,
    sent_at: datetime,
    seq: int | None = None,
    device_count: int | None = None,
) -> OutboundMessage:
    if state not in GATEWAY_STATES:
        raise ValueError(f"unsupported gateway state: {state}")
    payload: dict[str, Any] = {"state": state}
    if device_count is not None:
        if device_count < 0:
            raise ValueError("device_count must not be negative")
        payload["deviceCount"] = device_count
    return _build_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_type="GATEWAY_STATUS",
        channel="status",
        payload=payload,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
        retain=True,
    )


def build_device_inventory(
    *,
    gateway_id: str,
    boot_id: str,
    devices: Sequence[Device],
    message_id: UUID,
    sent_at: datetime,
    seq: int,
) -> OutboundMessage:
    if len(devices) > 100:
        raise ValueError("inventory must not contain more than 100 devices")
    payload = {"devices": [_inventory_device(device) for device in devices]}
    return _build_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_type="DEVICE_INVENTORY",
        channel="events",
        payload=payload,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
    )


def build_device_discovered(
    *,
    gateway_id: str,
    boot_id: str,
    device: Device,
    message_id: UUID,
    sent_at: datetime,
    seq: int,
) -> OutboundMessage:
    _validate_device(device)
    if len(device.metadata) > 32:
        raise ValueError("device metadata must not contain more than 32 properties")
    payload = _inventory_device(device)
    if device.metadata:
        payload["metadata"] = dict(device.metadata)
    return _build_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_type="DEVICE_DISCOVERED",
        channel="events",
        payload=payload,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
    )


def build_telemetry(
    *,
    gateway_id: str,
    boot_id: str,
    samples: Sequence[TelemetrySample],
    message_id: UUID,
    sent_at: datetime,
    seq: int,
) -> OutboundMessage:
    if not 1 <= len(samples) <= 500:
        raise ValueError("telemetry must contain between 1 and 500 samples")
    payload = {"samples": [_telemetry_sample(sample) for sample in samples]}
    return _build_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_type="TELEMETRY",
        channel="telemetry",
        payload=payload,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
    )


def build_security_event(
    *,
    gateway_id: str,
    boot_id: str,
    severity: str,
    code: str,
    message: str,
    detected_at: datetime,
    message_id: UUID,
    sent_at: datetime,
    seq: int,
    device_id: str | None = None,
    detector_name: str | None = None,
    detector_version: str | None = None,
    details: Mapping[str, Any] | None = None,
) -> OutboundMessage:
    if severity not in SECURITY_SEVERITIES:
        raise ValueError(f"unsupported security severity: {severity}")
    if not SECURITY_CODE_PATTERN.fullmatch(code):
        raise ValueError("security event code does not match the MQTT contract")
    if not 1 <= len(message) <= 2000:
        raise ValueError("security event message length does not match the MQTT contract")
    payload: dict[str, Any] = {
        "severity": severity,
        "code": code,
        "message": message,
        "detectedAt": format_utc_milliseconds(detected_at),
    }
    if device_id is not None:
        if not DEVICE_ID_PATTERN.fullmatch(device_id):
            raise ValueError("device_id does not match the MQTT contract")
        payload["deviceId"] = device_id
    if detector_name is not None:
        if not 1 <= len(detector_name) <= 64:
            raise ValueError("detector name length does not match the MQTT contract")
        detector = {"name": detector_name}
        if detector_version is not None:
            if len(detector_version) > 32:
                raise ValueError("detector version length does not match the MQTT contract")
            detector["version"] = detector_version
        payload["detector"] = detector
    elif detector_version is not None:
        raise ValueError("detector_version requires detector_name")
    if details is not None:
        if len(details) > 32:
            raise ValueError("security event details must not contain more than 32 properties")
        payload["details"] = dict(details)
    return _build_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_type="SECURITY_EVENT",
        channel="events",
        payload=payload,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
    )


def _build_message(
    *,
    gateway_id: str,
    boot_id: str,
    message_type: str,
    channel: str,
    payload: dict[str, Any],
    message_id: UUID,
    sent_at: datetime,
    seq: int | None,
    retain: bool = False,
) -> OutboundMessage:
    _validate_envelope_fields(gateway_id, boot_id, message_id, seq)
    document: dict[str, Any] = {
        "schemaVersion": 1,
        "messageId": str(message_id),
        "gatewayId": gateway_id,
        "type": message_type,
        "sentAt": format_utc_milliseconds(sent_at),
        "bootId": boot_id,
    }
    if seq is not None:
        document["seq"] = seq
    document["payload"] = payload
    document["signature"] = None
    serialized = json.dumps(
        document,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return OutboundMessage(
        topic=f"senso/v1/gw/{gateway_id}/{channel}",
        document=document,
        payload=serialized,
        retain=retain,
    )


def _validate_envelope_fields(
    gateway_id: str,
    boot_id: str,
    message_id: UUID,
    seq: int | None,
) -> None:
    if not GATEWAY_ID_PATTERN.fullmatch(gateway_id):
        raise ValueError("gateway_id does not match the MQTT contract")
    if not BOOT_ID_PATTERN.fullmatch(boot_id):
        raise ValueError("boot_id does not match the MQTT contract")
    if message_id.version != 7:
        raise ValueError("message_id must be UUID v7")
    if seq is not None and seq < 0:
        raise ValueError("seq must not be negative")


def _inventory_device(device: Device) -> dict[str, Any]:
    _validate_device(device)
    result: dict[str, Any] = {
        "deviceId": device.device_id,
        "deviceType": device.device_type,
    }
    if device.capabilities:
        result["capabilities"] = [_capability(capability) for capability in device.capabilities]
    return result


def _validate_device(device: Device) -> None:
    if not DEVICE_ID_PATTERN.fullmatch(device.device_id):
        raise ValueError("device_id does not match the MQTT contract")
    if not DEVICE_TYPE_PATTERN.fullmatch(device.device_type):
        raise ValueError("device_type does not match the MQTT contract")
    if len(device.capabilities) > 50:
        raise ValueError("device must not contain more than 50 capabilities")


def _capability(capability: Capability) -> dict[str, str]:
    if not METRIC_PATTERN.fullmatch(capability.metric):
        raise ValueError("metric does not match the MQTT contract")
    result = {"metric": capability.metric}
    if capability.unit is not None:
        if not 1 <= len(capability.unit) <= 32:
            raise ValueError("unit length does not match the MQTT contract")
        result["unit"] = capability.unit
    return result


def _telemetry_sample(sample: TelemetrySample) -> dict[str, Any]:
    if not DEVICE_ID_PATTERN.fullmatch(sample.device_id):
        raise ValueError("device_id does not match the MQTT contract")
    if not 1 <= len(sample.metrics) <= 20:
        raise ValueError("sample must contain between 1 and 20 metrics")
    metrics: dict[str, float] = {}
    for metric, value in sample.metrics.items():
        if not METRIC_PATTERN.fullmatch(metric):
            raise ValueError("metric does not match the MQTT contract")
        is_finite_number = (
            not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value)
        )
        if not is_finite_number:
            raise ValueError("metric value must be a finite number")
        metrics[metric] = value
    return {
        "deviceId": sample.device_id,
        "ts": format_utc_milliseconds(sample.sampled_at),
        "metrics": metrics,
    }


def format_utc_milliseconds(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    utc_value = value.astimezone(UTC)
    return utc_value.isoformat(timespec="milliseconds").replace("+00:00", "Z")
