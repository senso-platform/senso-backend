package dev.senso.core;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Общие переиспользуемые контейнеры для интеграционных тестов (AGENTS.md §8).
 * Теги закречены и совпадают с deploy/docker-compose.yml.
 */
public abstract class IntegrationTestBase {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("timescale/timescaledb:2.30.2-pg16").asCompatibleSubstituteFor("postgres");

    private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:4.3.6-management");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("senso")
            .withUsername("senso")
            .withPassword("integration-test")
            .withReuse(true);

    static final RabbitMQContainer RABBIT = new RabbitMQContainer(RABBIT_IMAGE).withReuse(true);

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }
}
