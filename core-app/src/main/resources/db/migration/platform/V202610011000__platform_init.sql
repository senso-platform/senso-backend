-- Платформенная схема: реестр событий Spring Modulith (outbox) и блокировки планировщиков.
-- DDL event_publication соответствует spring-modulith-events-jdbc 2.1.1 (схема V2, не legacy).

CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE IF NOT EXISTS platform.event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON platform.event_publication USING hash(serialized_event);

CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON platform.event_publication (completion_date);

CREATE TABLE IF NOT EXISTS platform.shedlock
(
    name       VARCHAR(64)          NOT NULL,
    lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by  VARCHAR(255)         NOT NULL,
    PRIMARY KEY (name)
);
