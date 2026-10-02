/**
 * Модуль identity: пользователи, JWT, refresh-токены. Схема БД {@code identity}.
 * Зависимости по AGENTS.md §3: ни на какие доменные модули (только инфраструктура {@code shared}).
 */
@ApplicationModule(allowedDependencies = {"shared"})
package dev.senso.core.identity;

import org.springframework.modulith.ApplicationModule;
