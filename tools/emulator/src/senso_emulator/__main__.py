from __future__ import annotations

import logging
import sys
from collections.abc import Sequence

from senso_emulator.config import ConfigError, load_config, parse_args
from senso_emulator.mqtt_transport import MqttTransportError
from senso_emulator.scenarios import (
    run_anomaly,
    run_discover,
    run_duplicate,
    run_normal,
    run_offline,
)


def main(argv: Sequence[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        args = parse_args(argv)
        config = load_config(args)
        if args.scenario == "normal":
            run_normal(config)
            return 0
        if args.scenario == "offline":
            run_offline(config)
            return 0
        if args.scenario == "anomaly":
            run_anomaly(config)
            return 0
        if args.scenario == "discover":
            run_discover(config)
            return 0
        if args.scenario == "duplicate":
            run_duplicate(config)
            return 0
        raise ConfigError("unsupported scenario")
    except ConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except MqttTransportError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
