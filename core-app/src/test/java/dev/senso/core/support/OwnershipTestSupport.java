package dev.senso.core.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.senso.kernel.error.NotFoundException;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Негативные тесты на владение (AGENTS.md §3.10, §7; ADR-0001 §10.2). Обязательны для каждого метода сервиса и
 * эндпоинта, который отдаёт или меняет данные пользователя.
 *
 * <p>Правило: чужой объект неотличим от несуществующего — сервис бросает {@link NotFoundException} (→ 404), а не
 * {@code ForbiddenException}. Пример (сервисный уровень):
 *
 * <pre>{@code
 * UUID gatewayId = gateways.create(OwnershipTestSupport.OWNER, "lab").id();
 * OwnershipTestSupport.assertHiddenFromStranger(() -> gateways.get(OwnershipTestSupport.STRANGER, gatewayId));
 * OwnershipTestSupport.assertHiddenFromStranger(() -> gateways.rename(OwnershipTestSupport.STRANGER, gatewayId, "x"));
 * }</pre>
 *
 * Для HTTP-уровня то же правило проверяется через статус 404 (появится вместе с security, PR identity).
 */
public final class OwnershipTestSupport {

    /** Владелец тестовых данных. */
    public static final UUID OWNER = UUID.fromString("00000000-0000-7000-8000-00000000000a");

    /** Другой пользователь: не должен ни видеть, ни менять объекты {@link #OWNER}. */
    public static final UUID STRANGER = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private OwnershipTestSupport() {}

    /** Вызов от имени чужого пользователя обязан закончиться {@link NotFoundException}. */
    public static void assertHiddenFromStranger(ThrowingCallable callAsStranger) {
        assertThatThrownBy(callAsStranger)
                .as("чужой объект должен выглядеть несуществующим: NotFoundException → 404, не 403 и не данные")
                .isInstanceOf(NotFoundException.class);
    }
}
