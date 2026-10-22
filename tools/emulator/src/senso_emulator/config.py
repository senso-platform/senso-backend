from __future__ import annotations

import argparse
import math
import os
import re
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field

GATEWAY_ID_PATTERN = re.compile(r"^[a-z0-9-]{3,64}$")


class ConfigError(ValueError):
    """A safe-to-display configuration error."""


@dataclass(frozen=True)
class EmulatorConfig:
    host: str
    port: int
    gateway_id: str
    password: str = field(repr=False)
    keepalive: int = 30
    timeout: float = 10.0
    device_count: int = 2
    discovered_device_count: int = 1
    telemetry_interval: float = 1.0
    duration: float = 10.0
    seed: int = 0
    once: bool = False

    @property
    def telemetry_batches(self) -> int:
        if self.once:
            return 1
        return max(1, math.ceil(self.duration / self.telemetry_interval))


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if any(_looks_like_password_option(value) for value in arguments):
        raise ConfigError("password must be provided via SENSO_EMULATOR_PASSWORD")

    parser = argparse.ArgumentParser(prog="senso-emulator")
    subparsers = parser.add_subparsers(dest="scenario", required=True)

    normal = subparsers.add_parser("normal", help="simulate one gateway session")
    _add_connection_arguments(normal)
    run_length = normal.add_mutually_exclusive_group()
    run_length.add_argument(
        "--once",
        action="store_true",
        help="publish exactly one telemetry batch without an interval delay",
    )
    run_length.add_argument(
        "--duration",
        type=float,
        help="bounded session duration in seconds (env: SENSO_EMULATOR_DURATION)",
    )
    normal.add_argument(
        "--devices",
        type=int,
        help="devices present in the initial inventory (env: SENSO_EMULATOR_DEVICE_COUNT)",
    )
    normal.add_argument(
        "--discovered-devices",
        type=int,
        help="new devices discovered after inventory (env: SENSO_EMULATOR_DISCOVERED_COUNT)",
    )
    normal.add_argument(
        "--interval",
        type=float,
        help="seconds between telemetry batches (env: SENSO_EMULATOR_INTERVAL)",
    )
    normal.add_argument(
        "--seed",
        type=int,
        help="deterministic telemetry seed (env: SENSO_EMULATOR_SEED)",
    )

    offline = subparsers.add_parser("offline", help="trigger the registered MQTT Last Will")
    _add_connection_arguments(offline)

    anomaly = subparsers.add_parser("anomaly", help="publish one gateway security event")
    _add_connection_arguments(anomaly)

    discover = subparsers.add_parser("discover", help="discover one device after inventory")
    _add_connection_arguments(discover)
    discover.add_argument(
        "--devices",
        type=int,
        help="devices present before discovery (env: SENSO_EMULATOR_DEVICE_COUNT)",
    )

    duplicate = subparsers.add_parser("duplicate", help="publish one logical event twice")
    _add_connection_arguments(duplicate)
    return parser.parse_args(arguments)


def _add_connection_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--host", help="MQTT broker host (env: SENSO_MQTT_HOST)")
    parser.add_argument("--port", type=int, help="MQTT broker port (env: SENSO_MQTT_PORT)")
    parser.add_argument(
        "--gateway-id",
        help="gateway ID and MQTT username (env: SENSO_EMULATOR_GATEWAY_ID)",
    )
    parser.add_argument(
        "--keepalive",
        type=int,
        help="MQTT keepalive seconds (env: SENSO_MQTT_KEEPALIVE)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        help="connect and PUBACK timeout seconds (env: SENSO_MQTT_TIMEOUT)",
    )


def _looks_like_password_option(value: str) -> bool:
    return (
        value in {"-P", "--passwd", "--password"}
        or value.startswith("-P=")
        or (value.startswith("-P") and len(value) > 2)
        or value.startswith("--passwd=")
        or value.startswith("--password=")
    )


def load_config(
    args: argparse.Namespace,
    environ: Mapping[str, str] | None = None,
) -> EmulatorConfig:
    env = os.environ if environ is None else environ
    password = env.get("SENSO_EMULATOR_PASSWORD")
    if not password:
        raise ConfigError("SENSO_EMULATOR_PASSWORD is required")

    host = args.host or env.get("SENSO_MQTT_HOST", "localhost")
    gateway_id = args.gateway_id or env.get("SENSO_EMULATOR_GATEWAY_ID", "gw-emulator-01")
    port = args.port if args.port is not None else _env_int(env, "SENSO_MQTT_PORT", 1883)
    keepalive = (
        args.keepalive if args.keepalive is not None else _env_int(env, "SENSO_MQTT_KEEPALIVE", 30)
    )
    timeout = (
        args.timeout if args.timeout is not None else _env_float(env, "SENSO_MQTT_TIMEOUT", 10.0)
    )
    devices = getattr(args, "devices", None)
    discovered_devices = getattr(args, "discovered_devices", None)
    interval = getattr(args, "interval", None)
    requested_duration = getattr(args, "duration", None)
    requested_seed = getattr(args, "seed", None)
    once = getattr(args, "once", False)
    device_count = (
        devices if devices is not None else _env_int(env, "SENSO_EMULATOR_DEVICE_COUNT", 2)
    )
    discovered_device_count = (
        discovered_devices
        if discovered_devices is not None
        else _env_int(env, "SENSO_EMULATOR_DISCOVERED_COUNT", 1)
    )
    telemetry_interval = (
        interval if interval is not None else _env_float(env, "SENSO_EMULATOR_INTERVAL", 1.0)
    )
    duration = (
        0.0
        if once
        else (
            requested_duration
            if requested_duration is not None
            else _env_float(env, "SENSO_EMULATOR_DURATION", 10.0)
        )
    )
    seed = requested_seed if requested_seed is not None else _env_int(env, "SENSO_EMULATOR_SEED", 0)

    if not host.strip():
        raise ConfigError("MQTT host must not be blank")
    if not GATEWAY_ID_PATTERN.fullmatch(gateway_id):
        raise ConfigError("gateway ID must match ^[a-z0-9-]{3,64}$")
    if not 1 <= port <= 65535:
        raise ConfigError("MQTT port must be between 1 and 65535")
    if not 1 <= keepalive <= 65535:
        raise ConfigError("MQTT keepalive must be between 1 and 65535 seconds")
    if not math.isfinite(timeout) or timeout <= 0:
        raise ConfigError("timeout must be greater than zero")
    if not 0 <= device_count <= 100:
        raise ConfigError("device count must be between 0 and 100")
    if not 0 <= discovered_device_count <= 50:
        raise ConfigError("discovered device count must be between 0 and 50")
    if not 1 <= device_count + discovered_device_count <= 100:
        raise ConfigError("total device count must be between 1 and 100")
    if not math.isfinite(telemetry_interval) or telemetry_interval <= 0:
        raise ConfigError("telemetry interval must be greater than zero")
    if not once and (not math.isfinite(duration) or duration <= 0):
        raise ConfigError("duration must be greater than zero")

    return EmulatorConfig(
        host=host,
        port=port,
        gateway_id=gateway_id,
        password=password,
        keepalive=keepalive,
        timeout=timeout,
        device_count=device_count,
        discovered_device_count=discovered_device_count,
        telemetry_interval=telemetry_interval,
        duration=duration,
        seed=seed,
        once=once,
    )


def _env_int(env: Mapping[str, str], name: str, default: int) -> int:
    value = env.get(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer") from exc


def _env_float(env: Mapping[str, str], name: str, default: float) -> float:
    value = env.get(name)
    if value is None:
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be a number") from exc
