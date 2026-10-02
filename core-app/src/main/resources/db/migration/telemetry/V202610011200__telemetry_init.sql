-- Схема telemetry: hypertable измерений (ADR-0001 §8.3).
-- Расшишение в локальной БД создаёт deploy/postgres/init; в чистых БД тестов нужно здесь.
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE SCHEMA IF NOT EXISTS telemetry;

CREATE TABLE telemetry.measurements
(
    ts          timestamptz      NOT NULL,
    device_id   uuid             NOT NULL,
    metric      varchar(64)      NOT NULL,
    value       double precision NOT NULL,
    received_at timestamptz      NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, metric, ts)
);

SELECT create_hypertable('telemetry.measurements', 'ts', chunk_time_interval => INTERVAL '1 day');
