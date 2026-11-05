from __future__ import annotations

import copy
import json
from collections.abc import Callable
from datetime import datetime, timedelta
from typing import Any
from uuid import UUID

from senso_emulator.messages import (
    OutboundMessage,
    TelemetrySample,
    build_telemetry,
    format_utc_milliseconds,
)

INVALID_MESSAGE_VARIANTS = frozenset(
    {
        "broken-json",
        "missing-message-id",
        "wrong-type",
        "too-many-samples",
        "future-timestamp",
        "gateway-mismatch",
        "oversized",
    }
)

INGEST_MAX_MESSAGE_BYTES = 262_144
RABBITMQ_MAX_MESSAGE_BYTES = 524_288
FUTURE_TIMESTAMP_OFFSET = timedelta(minutes=10)


def build_invalid_message(
    variant: str,
    *,
    gateway_id: str,
    boot_id: str,
    message_id: UUID,
    sent_at: datetime,
    seq: int = 1,
) -> OutboundMessage:
    """Build one intentionally invalid TELEMETRY message for a negative test scenario."""
    if variant not in INVALID_MESSAGE_VARIANTS:
        supported = ", ".join(sorted(INVALID_MESSAGE_VARIANTS))
        raise ValueError(
            f"unsupported invalid message variant: {variant}; expected one of {supported}"
        )

    if variant == "oversized":
        return _build_oversized_message(
            gateway_id=gateway_id,
            boot_id=boot_id,
            message_id=message_id,
            sent_at=sent_at,
            seq=seq,
        )

    valid = _build_base_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
        sample_count=500 if variant == "too-many-samples" else 1,
    )
    if variant == "broken-json":
        return OutboundMessage(
            topic=valid.topic,
            document=copy.deepcopy(valid.document),
            payload=valid.payload[:-1],
            qos=valid.qos,
            retain=valid.retain,
        )

    mutations: dict[str, Callable[[dict[str, Any]], None]] = {
        "missing-message-id": lambda document: document.pop("messageId"),
        "wrong-type": lambda document: document.__setitem__("type", "DEVICE_PING"),
        "too-many-samples": _append_extra_sample,
        "future-timestamp": lambda document: document.__setitem__(
            "sentAt", format_utc_milliseconds(sent_at + FUTURE_TIMESTAMP_OFFSET)
        ),
        "gateway-mismatch": lambda document: document.__setitem__(
            "gatewayId", _different_gateway_id(gateway_id)
        ),
    }
    return _mutate(valid, mutations[variant])


def _build_base_message(
    *,
    gateway_id: str,
    boot_id: str,
    message_id: UUID,
    sent_at: datetime,
    seq: int,
    sample_count: int,
    metrics: dict[str, float] | None = None,
) -> OutboundMessage:
    sample_metrics = metrics or {"temperature": 21.5}
    samples = [
        TelemetrySample(
            device_id=f"sensor-{index:03d}",
            sampled_at=sent_at,
            metrics=sample_metrics,
        )
        for index in range(sample_count)
    ]
    return build_telemetry(
        gateway_id=gateway_id,
        boot_id=boot_id,
        samples=samples,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
    )


def _build_oversized_message(
    *,
    gateway_id: str,
    boot_id: str,
    message_id: UUID,
    sent_at: datetime,
    seq: int,
) -> OutboundMessage:
    metrics: dict[str, float] = {}
    for index in range(8):
        prefix = f"metric_{index:02d}_"
        metrics[prefix + "x" * (64 - len(prefix))] = float(index)

    message = _build_base_message(
        gateway_id=gateway_id,
        boot_id=boot_id,
        message_id=message_id,
        sent_at=sent_at,
        seq=seq,
        sample_count=500,
        metrics=metrics,
    )
    if not INGEST_MAX_MESSAGE_BYTES < len(message.payload) < RABBITMQ_MAX_MESSAGE_BYTES:
        raise AssertionError("oversized test payload is outside the ingest/RabbitMQ size window")
    return message


def _mutate(
    valid: OutboundMessage,
    mutation: Callable[[dict[str, Any]], None],
) -> OutboundMessage:
    document = copy.deepcopy(valid.document)
    mutation(document)
    payload = json.dumps(
        document,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return OutboundMessage(
        topic=valid.topic,
        document=document,
        payload=payload,
        qos=valid.qos,
        retain=valid.retain,
    )


def _append_extra_sample(document: dict[str, Any]) -> None:
    samples = document["payload"]["samples"]
    samples.append(copy.deepcopy(samples[-1]))


def _different_gateway_id(gateway_id: str) -> str:
    return "gw-mismatch-02" if gateway_id == "gw-mismatch-01" else "gw-mismatch-01"
