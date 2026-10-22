package dev.senso.ingest.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RabbitSettingsGuardTest {

    @Test
    void failsWhenPlaceholdersAreNotResolved() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.rabbitmq.host", "${SENSO_RABBIT_HOST}")
                .withProperty("spring.rabbitmq.username", "${SENSO_RABBIT_USER}")
                .withProperty("spring.rabbitmq.password", "${SENSO_RABBIT_PASSWORD}");

        assertThatThrownBy(() -> new RabbitSettingsGuard(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile local")
                .hasMessageContaining("spring.rabbitmq.host");
    }

    @Test
    void failsWhenSettingIsMissing() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.rabbitmq.host", "localhost");

        assertThatThrownBy(() -> new RabbitSettingsGuard(environment).afterPropertiesSet())
                .hasMessageContaining("spring.rabbitmq.username")
                .hasMessageContaining("spring.rabbitmq.password");
    }

    @Test
    void passesWhenAllSettingsAreResolved() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.rabbitmq.host", "localhost")
                .withProperty("spring.rabbitmq.username", "senso-ingest")
                .withProperty("spring.rabbitmq.password", "secret");

        assertThatCode(() -> new RabbitSettingsGuard(environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
