-- Расширения PostgreSQL, нужные модулям. Единственное место, где в миграциях появляется CREATE EXTENSION
-- (AGENTS.md §5). IF NOT EXISTS: на dev-стенде/managed-БД расширения заранее ставит администратор, и тогда
-- здесь no-op; в чистой БД Testcontainers они создаются этой миграцией (init-скрипты deploy/ туда не монтируются).
--
-- timescaledb — telemetry (hypertable, continuous aggregates).
-- citext      — identity (регистронезависимый email). Расширение trusted: владелец БД ставит без суперпользователя.
--
-- Версия раньше platform_init намеренно: в уже мигрированной локальной БД применится как out-of-order
-- (spring.flyway.out-of-order=true), в новой — первой.
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS citext;
