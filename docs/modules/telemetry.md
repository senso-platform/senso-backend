# telemetry

Приём валидированной телеметрии из RabbitMQ (`q.telemetry.writer`, роль worker), запись в hypertable
`telemetry.measurements`, графики через continuous aggregates. Схема БД `telemetry`.
Разрешённая зависимость: `devices::api`.
