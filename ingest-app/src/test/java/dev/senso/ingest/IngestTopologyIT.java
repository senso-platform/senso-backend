package dev.senso.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.senso.contracts.messaging.Exchanges;
import dev.senso.contracts.messaging.Headers;
import dev.senso.contracts.messaging.Queues;
import dev.senso.contracts.messaging.RoutingKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Топология ingest на брокере с deploy/rabbitmq/definitions.json: объявления приложения совпадают с файлом
 * (иначе контекст не поднимет листенер), сообщение из {@code amq.topic} доходит до листенера и уходит из
 * {@code q.ingest.raw}. Полный поток по примерам контракта — {@code IngestFlowIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IngestTopologyIT extends IngestIntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void rawQueueMatchesDefinitionsAndIsBoundToMqttTopic() throws Exception {
        JsonNode arguments =
                management("/api/queues/" + VHOST + "/" + Queues.INGEST_RAW).get("arguments");
        assertThat(arguments.get("x-queue-type").asString()).isEqualTo("quorum");
        assertThat(arguments.get("x-dead-letter-exchange").asString()).isEqualTo(Exchanges.DLQ);
        assertThat(arguments.get("x-dead-letter-routing-key").asString()).isEqualTo(RoutingKeys.DLQ_FAILED);
        assertThat(arguments.get("x-delivery-limit").asInt()).isEqualTo(5);

        JsonNode bindings = management("/api/exchanges/" + VHOST + "/" + Exchanges.AMQ_TOPIC + "/bindings/source");
        assertThat(bindings.valueStream()).anySatisfy(b -> {
            assertThat(b.get("destination").asString()).isEqualTo(Queues.INGEST_RAW);
            assertThat(b.get("routing_key").asString()).isEqualTo(RoutingKeys.MQTT_ALL);
        });
    }

    @Test
    void messageFromMqttExchangeIsConsumedAndRejectedToDlq() {
        amqpAdmin.purgeQueue(Queues.DLQ_INVALID, false);
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);

        rabbitTemplate.send(
                Exchanges.AMQ_TOPIC,
                RoutingKeys.MQTT_PREFIX + ".gw-emulator-01.telemetry",
                new Message(body, new MessageProperties()));

        Message rejected = rabbitTemplate.receive(
                Queues.DLQ_INVALID, Duration.ofSeconds(10).toMillis());
        assertThat(rejected).as("сообщение в q.dlq.invalid").isNotNull();
        assertThat(rejected.getBody()).isEqualTo(body);
        assertThat((String) rejected.getMessageProperties().getHeader(Headers.X_REJECT_REASON))
                .isEqualTo("ENVELOPE_SCHEMA");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                amqpAdmin.getQueueInfo(Queues.INGEST_RAW).getMessageCount())
                        .isZero());
    }

    private JsonNode management(String path) throws Exception {
        String auth = Base64.getEncoder()
                .encodeToString((ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }
}
