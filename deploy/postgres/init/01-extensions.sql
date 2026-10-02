-- Выполняется один раз при создании volume (docker-entrypoint-initdb.d).
-- Схемы и таблицы создаёт Flyway из core-app, здесь только расширения.
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS citext;
