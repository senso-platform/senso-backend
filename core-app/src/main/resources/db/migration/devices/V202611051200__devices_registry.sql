CREATE SCHEMA IF NOT EXISTS devices;

CREATE TABLE devices.gateways (
    id                 uuid PRIMARY KEY,
    owner_id           uuid NOT NULL,
    external_id        varchar(64) NOT NULL UNIQUE CHECK (external_id ~ '^[a-z0-9-]{3,64}$'),
    name               varchar(128) NOT NULL,
    mqtt_password_hash varchar(255) NOT NULL,
    state              varchar(16) NOT NULL DEFAULT 'UNKNOWN'
                       CHECK (state IN ('UNKNOWN', 'ONLINE', 'DEGRADED', 'OFFLINE')),
    boot_id            varchar(64),
    online_observed    boolean NOT NULL DEFAULT false,
    status_sent_at     timestamptz,
    inventory_at       timestamptz,
    last_seen_at       timestamptz,
    firmware           varchar(64),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE INDEX ix_gateways_owner_created ON devices.gateways (owner_id, created_at DESC, id DESC);

CREATE TABLE devices.devices (
    id                       uuid PRIMARY KEY,
    gateway_id               uuid NOT NULL REFERENCES devices.gateways(id) ON DELETE CASCADE,
    external_id              varchar(64) NOT NULL CHECK (external_id ~ '^[a-z0-9-]{1,64}$'),
    name                     varchar(128),
    device_type              varchar(32) NOT NULL CHECK (device_type ~ '^[A-Z0-9_]{2,32}$'),
    status                   varchar(16) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'DISABLED')),
    capabilities             jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(capabilities) = 'array'),
    metadata                 jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    connectivity             varchar(16) NOT NULL DEFAULT 'UNKNOWN'
                             CHECK (connectivity IN ('UNKNOWN', 'ONLINE', 'OFFLINE')),
    connectivity_observed_at timestamptz,
    last_seen_at             timestamptz,
    approved_by              uuid,
    approved_at              timestamptz,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    UNIQUE (gateway_id, external_id)
);

CREATE INDEX ix_devices_gateway_status ON devices.devices (gateway_id, status);
CREATE INDEX ix_devices_gateway_created ON devices.devices (gateway_id, created_at DESC, id DESC);
CREATE INDEX ix_devices_created ON devices.devices (created_at DESC, id DESC);

-- Discovery/inventory deduplication is committed atomically with the registry update.
-- GATEWAY_STATUS intentionally bypasses this table: Last Will message ids are static.
CREATE TABLE devices.processed_messages (
    gateway_id   uuid NOT NULL REFERENCES devices.gateways(id) ON DELETE CASCADE,
    message_id   uuid NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (gateway_id, message_id)
);
