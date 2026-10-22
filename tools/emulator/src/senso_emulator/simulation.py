from __future__ import annotations

import random
from dataclasses import dataclass
from datetime import datetime

from senso_emulator.messages import Capability, Device, TelemetrySample


@dataclass(frozen=True)
class DevicePlan:
    inventory: tuple[Device, ...]
    discovered: tuple[Device, ...]

    @property
    def all_devices(self) -> tuple[Device, ...]:
        return self.inventory + self.discovered


def build_device_plan(inventory_count: int, discovered_count: int) -> DevicePlan:
    devices = tuple(_device(index) for index in range(1, inventory_count + discovered_count + 1))
    return DevicePlan(
        inventory=devices[:inventory_count],
        discovered=devices[inventory_count:],
    )


def generate_telemetry_samples(
    devices: tuple[Device, ...],
    sampled_at: datetime,
    random_source: random.Random,
) -> tuple[TelemetrySample, ...]:
    return tuple(
        TelemetrySample(
            device_id=device.device_id,
            sampled_at=sampled_at,
            metrics={
                capability.metric: _metric_value(capability.metric, random_source)
                for capability in device.capabilities
            },
        )
        for device in devices
    )


def _device(index: int) -> Device:
    if index % 2:
        device_type = "TEMPERATURE"
        capabilities = (
            Capability("temperature", "CEL"),
            Capability("humidity", "PCT"),
        )
    else:
        device_type = "AIR_QUALITY"
        capabilities = (
            Capability("temperature", "CEL"),
            Capability("co2_ppm", "PPM"),
        )
    return Device(
        device_id=f"sensor-{index:03d}",
        device_type=device_type,
        capabilities=capabilities,
        metadata={"firmware": "1.0.0", "busAddress": f"0x{index:02X}"},
    )


def _metric_value(metric: str, random_source: random.Random) -> float:
    ranges = {
        "temperature": (18.0, 26.0),
        "humidity": (35.0, 65.0),
        "co2_ppm": (450.0, 900.0),
    }
    lower, upper = ranges[metric]
    return round(random_source.uniform(lower, upper), 2)
