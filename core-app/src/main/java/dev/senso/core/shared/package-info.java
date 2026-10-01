/**
 * Инфраструктура приложения, общая для всех модулей: роли ({@code role}), веб-слой ({@code web}),
 * планировщики ({@code scheduling}), позже безопасность ({@code security}). Доменной логики здесь нет.
 *
 * <p>Тип OPEN: подпакеты {@code shared.*} видны остальным модулям (по умолчанию Modulith считает подпакеты
 * внутренними, и первый же {@code @ApiRole} в чужом модуле ронял бы {@code verify()}). Каждый модуль
 * дополнительно перечисляет {@code "shared"} в своих {@code allowedDependencies}.
 *
 * <p>{@code shared} не зависит ни от одного доменного модуля — это проверяет {@code allowedDependencies = {}}.
 */
@ApplicationModule(
        type = ApplicationModule.Type.OPEN,
        allowedDependencies = {})
package dev.senso.core.shared;

import org.springframework.modulith.ApplicationModule;
