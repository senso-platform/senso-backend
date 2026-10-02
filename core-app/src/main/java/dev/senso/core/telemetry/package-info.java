/**
 * Модуль telemetry: приём и хранение измерений (TimescaleDB), графики. Схема БД {@code telemetry}.
 * Разрешённые зависимости: {@code shared}, {@code devices::api}.
 */
@ApplicationModule(allowedDependencies = {"shared", "devices::api"})
package dev.senso.core.telemetry;

import org.springframework.modulith.ApplicationModule;
