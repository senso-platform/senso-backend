/**
 * Модуль alerts: алерты от IDS шлюза, жизненный цикл, правила. Схема БД {@code alerts}.
 * Разрешённые зависимости: {@code shared}, {@code devices::api}.
 */
@ApplicationModule(allowedDependencies = {"shared", "devices::api"})
package dev.senso.core.alerts;

import org.springframework.modulith.ApplicationModule;
