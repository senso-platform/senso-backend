package dev.senso.core;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Общие переиспользуемые контейнеры для интеграционных тестов (AGENTS.md §8).
 * Теги закреплены и совпадают с deploy/docker-compose.yml.
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

    private static final String TEST_PUBLIC_KEY;
    private static final String TEST_PRIVATE_KEY;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);

            KeyPair keyPair = generator.generateKeyPair();

            TEST_PUBLIC_KEY = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());

            TEST_PRIVATE_KEY = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate test RSA keys", e);
        }

        POSTGRES.start();
        RABBIT.start();
    }

    private static String toPem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----";
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {

        // PostgreSQL
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // RabbitMQ
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");

        // JWT: временные ключи только для интеграционных тестов
        registry.add("senso.jwt.public-key", () -> TEST_PUBLIC_KEY);
        registry.add("senso.jwt.private-key", () -> TEST_PRIVATE_KEY);
    }
}
