-- flyway:executeInTransaction=false
-- Continuous aggregates и политики жизненного цикла (ADR-0001 §8.3, TimescaleDB 2.30).
-- CREATE MATERIALIZED VIEW ... (timescaledb.continuous) и политики вне транзакции не выполняются.

CREATE MATERIALIZED VIEW telemetry.measurements_1m
    WITH (timescaledb.continuous) AS
SELECT time_bucket('1 minute', ts) AS bucket,
       device_id,
       metric,
       avg(value)                  AS avg_value,
       min(value)                  AS min_value,
       max(value)                  AS max_value,
       count(*)                    AS samples
FROM telemetry.measurements
GROUP BY bucket, device_id, metric
WITH NO DATA;

-- _1h считается из сырых данных, а не поверх _1m: минmax-повторная агрегация
-- cagg-на-cagg в TimescaleDB 2.30 запрещена (проверено на 2.30.2).
CREATE MATERIALIZED VIEW telemetry.measurements_1h
    WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', ts) AS bucket,
       device_id,
       metric,
       avg(value)                AS avg_value,
       min(value)                AS min_value,
       max(value)                AS max_value,
       count(*)                  AS samples
FROM telemetry.measurements
GROUP BY bucket, device_id, metric
WITH NO DATA;

ALTER TABLE telemetry.measurements
    SET (timescaledb.compress,
         timescaledb.compress_segmentby = 'device_id, metric',
         timescaledb.compress_orderby = 'ts DESC');

-- Сжатие с 7 суток проверено на 2.30.2: refresh по сжатому чанку читает данные корректно,
-- и опоздавший сэмпл в уже сжатый чанк вставляется (чанк остаётся compressed=true).
SELECT add_compression_policy('telemetry.measurements', INTERVAL '7 days');
SELECT add_retention_policy('telemetry.measurements', INTERVAL '90 days');
SELECT add_retention_policy('telemetry.measurements_1m', INTERVAL '1 year');
SELECT add_retention_policy('telemetry.measurements_1h', INTERVAL '5 years');

-- Окно переза агрегации (start_offset − end_offset) задаётся по договору с шлюзом:
-- после обрыва он досылает буфер до 7 суток (contracts/mqtt/TOPICS.md). Если окно уже
-- не покрывается политикой, позднее измерение ляжется в сырую таблицу, но в агрегат не
-- попадёт никогда, и график будет врать.
--   _1m: 1 сутки. 7 суток минутных бакетов каждый минут — это ~10 000 бакетов на группу
--        (device_id, metric) за прогон; для часового окна достаточно _1h. Досыл старше суток
--        materialизуется в _1h и остаётся в сырых данных, точечный refresh за интервал пакета
--        (CALL refresh_continuous_aggregate — в 2.30 это процедура) добавим в S2 в worker.
--   _1h: 7 суток, ровно горизонт досыла.
-- end_offset обязателен и окно обязано покрывать минимум 2 бакета (иначе 22023
-- "policy refresh window too small"). Публичное имя функции в 2.30 —
-- add_continuous_aggregate_policy (add_refresh_policy отсутствует).
SELECT add_continuous_aggregate_policy('telemetry.measurements_1m',
                                       start_offset => INTERVAL '1 day',
                                       end_offset => INTERVAL '1 minute',
                                       schedule_interval => INTERVAL '1 minute');
SELECT add_continuous_aggregate_policy('telemetry.measurements_1h',
                                       start_offset => INTERVAL '7 days',
                                       end_offset => INTERVAL '1 hour',
                                       schedule_interval => INTERVAL '1 hour');
