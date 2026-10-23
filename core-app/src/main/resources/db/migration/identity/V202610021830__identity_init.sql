
CREATE SCHEMA IF NOT EXISTS identity;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE identity.users (
                                id            uuid PRIMARY KEY,
                                email         citext       NOT NULL UNIQUE,
                                display_name  varchar(128) NOT NULL,
                                password_hash varchar(255) NOT NULL,
                                status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
                                created_at    timestamptz  NOT NULL DEFAULT now(),
                                updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE identity.user_roles (
                                     user_id uuid        NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
                                     role    varchar(32) NOT NULL,
                                     PRIMARY KEY (user_id, role)
);

CREATE TABLE identity.refresh_tokens (
                                         id          uuid PRIMARY KEY,
                                         user_id     uuid        NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
                                         family_id   uuid        NOT NULL,
                                         token_hash  bytea       NOT NULL UNIQUE,
                                         expires_at  timestamptz NOT NULL,
                                         used_at     timestamptz,
                                         revoked_at  timestamptz,
                                         created_at  timestamptz NOT NULL DEFAULT now(),
                                         user_agent  varchar(256)
);

CREATE INDEX ix_refresh_family
    ON identity.refresh_tokens (family_id);
