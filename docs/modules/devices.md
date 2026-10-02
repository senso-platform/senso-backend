# devices

Шлюзы и устройства: реестр, привязка к владельцу, ACL. Схема БД `devices`.
Разрешённая зависимость: `identity::api`.
В S1 каркас; в S2 здесь появится `DeviceQueries.resolve(gatewayId, deviceId)` для telemetry.
