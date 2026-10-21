package dev.senso.ingest.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.senso.contracts.internal.ValidatedMessageV1;
import dev.senso.contracts.mqtt.MessageType;
import dev.senso.contracts.mqtt.MqttChannels;
import dev.senso.ingest.ContractExamples;
import dev.senso.ingest.config.IngestProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Валидатор без Spring-контекста: примеры из contracts/mqtt/examples и отдельные кейсы (S2, задача 4). */
class MessageValidatorTest {

    /** Чуть позже sentAt всех валидных примеров (2026-09-25T10:13:59Z … 10:15:30Z), в пределах 5 минут. */
    private static final Instant NOW = Instant.parse("2026-09-25T10:16:00Z");

    private static final long MAX_BYTES = 262_144;
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final String GATEWAY = "gw-emulator-01";

    private static final MqttSchemas SCHEMAS = new MqttSchemas();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MessageValidator validator = validator(MAX_BYTES, false, (envelope, message) -> true);

    // --- примеры из контракта ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("validExamples")
    void validExamplePasses(Path example) throws IOException {
        byte[] body = Files.readAllBytes(example);
        ObjectNode json = (ObjectNode) JSON.readTree(body);
        MessageType type = MessageType.valueOf(json.get("type").asString());

        ValidatedMessageV1 validated =
                validator.validate(address(json.get("gatewayId").asString(), type), body);

        assertThat(validated.envelope().type()).isEqualTo(type);
        assertThat(validated.envelope().messageId().toString())
                .isEqualTo(json.get("messageId").asString());
        assertThat(validated.gatewayId()).isEqualTo(GATEWAY);
        assertThat(validated.receivedAt()).isEqualTo(NOW);
        assertThat(validated.clockSkew()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidExamples")
    void invalidExampleIsRejectedWithExpectedReason(Path example) throws IOException {
        RejectReason expected = ContractExamples.expectedReason(example);
        byte[] body = Files.readAllBytes(example);

        // тип в примере может быть невалидным — канал берём telemetry: все невалидные примеры про телеметрию
        assertRejected(() -> validator.validate(new TopicAddress(GATEWAY, MqttChannels.TELEMETRY), body), expected);
    }

    @Test
    void everyMappedInvalidExampleExists() {
        assertThat(invalidExamples().map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrderElementsOf(ContractExamples.INVALID_REASONS.keySet());
    }

    // --- порядок и отдельные проверки ---

    @Test
    void rejectsOversizedMessageFirst() {
        MessageValidator small = validator(64, false, (envelope, message) -> true);
        byte[] body = telemetry(json -> {});

        assertRejected(
                () -> small.validate(new TopicAddress(GATEWAY, "unknown"), body), RejectReason.PAYLOAD_TOO_LARGE);
    }

    @Test
    void acceptsMessageExactlyAtSizeLimit() {
        byte[] body = telemetry(json -> {});
        MessageValidator exact = validator(body.length, false, (envelope, message) -> true);

        assertThat(exact.validate(telemetryAddress(), body)).isNotNull();
    }

    @Test
    void rejectsUnknownOrOutboundChannel() {
        byte[] body = telemetry(json -> {});

        assertRejected(() -> validator.validate(new TopicAddress(GATEWAY, "ack"), body), RejectReason.UNKNOWN_CHANNEL);
        assertRejected(() -> validator.validate(new TopicAddress(GATEWAY, "cmd"), body), RejectReason.UNKNOWN_CHANNEL);
    }

    @Test
    void rejectsNonJson() {
        byte[] body = "temperature=4.2".getBytes(StandardCharsets.UTF_8);

        assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.MALFORMED_JSON);
    }

    @Test
    void rejectsEmptyBody() {
        assertRejected(() -> validator.validate(telemetryAddress(), new byte[0]), RejectReason.MALFORMED_JSON);
    }

    @Test
    void rejectsJsonArray() {
        byte[] body = "[{\"type\":\"TELEMETRY\"}]".getBytes(StandardCharsets.UTF_8);

        assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.MALFORMED_JSON);
    }

    @Test
    void rejectsSentAtThatIsNotADate() {
        byte[] body = telemetry(json -> json.put("sentAt", "not-a-date"));

        MessageRejectionException e =
                assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.ENVELOPE_SCHEMA);
        assertThat(e.getMessage()).contains("sentAt");
    }

    @Test
    void rejectsMessageIdThatIsNotAUuid() {
        byte[] body = telemetry(json -> json.put("messageId", "42"));

        assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.ENVELOPE_SCHEMA);
    }

    @Test
    void rejectsGatewayMismatch() {
        byte[] body = telemetry(json -> {});

        assertRejected(
                () -> validator.validate(new TopicAddress("gw-other-02", MqttChannels.TELEMETRY), body),
                RejectReason.GATEWAY_MISMATCH);
    }

    @Test
    void rejectionCarriesMessageIdAndTypeWhenReadable() {
        byte[] body = telemetry(json -> {});

        MessageRejectionException e = assertRejected(
                () -> validator.validate(new TopicAddress("gw-other-02", MqttChannels.TELEMETRY), body),
                RejectReason.GATEWAY_MISMATCH);

        assertThat(e.messageId()).isEqualTo("0195f3c2-7d1e-7a4b-9c11-2b6f0e5a8d34");
        assertThat(e.type()).isEqualTo(MessageType.TELEMETRY);
    }

    @Test
    void rejectionContextIgnoresGarbageMessageIdAndType() {
        byte[] body = telemetry(json -> json.put("messageId", "x".repeat(36)).put("type", "DEVICE_PING"));

        MessageRejectionException e =
                assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.ENVELOPE_SCHEMA);

        assertThat(e.messageId()).isNull();
        assertThat(e.type()).isNull();
    }

    @Test
    void rejectsTypeFromAnotherChannel() {
        byte[] body = telemetry(json -> {});

        assertRejected(
                () -> validator.validate(new TopicAddress(GATEWAY, MqttChannels.STATUS), body),
                RejectReason.CHANNEL_TYPE_MISMATCH);
        assertRejected(
                () -> validator.validate(new TopicAddress(GATEWAY, MqttChannels.EVENTS), body),
                RejectReason.CHANNEL_TYPE_MISMATCH);
    }

    @Test
    void rejectsSampleTimestampThatIsNotADate() {
        byte[] body = telemetry(json -> firstSample(json).put("ts", "yesterday"));

        MessageRejectionException e =
                assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.PAYLOAD_SCHEMA);
        assertThat(e.getMessage()).contains("samples");
    }

    @Test
    void rejectsSampleTimestampInFuture() {
        byte[] body = telemetry(json ->
                firstSample(json).put("ts", NOW.plus(SKEW).plusSeconds(1).toString()));

        MessageRejectionException e =
                assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.TIMESTAMP_IN_FUTURE);
        assertThat(e.getMessage()).contains("samples[0].ts");
    }

    @Test
    void acceptsTimestampsAtTheEdgeOfAllowedSkew() {
        String edge = NOW.plus(SKEW).toString();
        byte[] body = telemetry(json -> {
            json.put("sentAt", edge);
            firstSample(json).put("ts", edge);
        });

        ValidatedMessageV1 validated = validator.validate(telemetryAddress(), body);

        assertThat(validated.clockSkew()).isFalse();
    }

    @Test
    void sentAtFarInPastIsAcceptedWithClockSkewFlag() {
        byte[] body = telemetry(
                json -> json.put("sentAt", NOW.minus(Duration.ofHours(3)).toString()));

        ValidatedMessageV1 validated = validator.validate(telemetryAddress(), body);

        assertThat(validated.clockSkew()).isTrue();
    }

    @Test
    void rejectsInvalidSignatureOnlyWhenRequired() {
        byte[] body = telemetry(json -> {});
        SignatureVerifier alwaysInvalid = (envelope, message) -> false;

        assertThat(validator(MAX_BYTES, false, alwaysInvalid).validate(telemetryAddress(), body))
                .isNotNull();
        assertRejected(
                () -> validator(MAX_BYTES, true, alwaysInvalid).validate(telemetryAddress(), body),
                RejectReason.SIGNATURE_INVALID);
    }

    @Test
    void detailIsShortAndDoesNotContainWholeMessage() {
        String longGateway = "gw-" + "x".repeat(2_000);
        byte[] body = telemetry(json -> json.put("bootId", longGateway));

        MessageRejectionException e =
                assertRejected(() -> validator.validate(telemetryAddress(), body), RejectReason.ENVELOPE_SCHEMA);
        assertThat(e.getMessage()).hasSizeLessThanOrEqualTo(MessageRejectionException.MAX_DETAIL);
    }

    // --- helpers ---

    private static MessageValidator validator(long maxBytes, boolean signatureRequired, SignatureVerifier verifier) {
        IngestProperties properties = new IngestProperties(maxBytes, SKEW, Duration.ofSeconds(5), signatureRequired);
        return new MessageValidator(SCHEMAS, JSON, verifier, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MessageRejectionException assertRejected(ThrowingCallable call, RejectReason reason) {
        MessageRejectionException[] caught = new MessageRejectionException[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(MessageRejectionException.class, e -> {
            assertThat(e.reason()).as(e.getMessage()).isEqualTo(reason);
            assertThat(e.getMessage()).isNotBlank();
            caught[0] = e;
        });
        return caught[0];
    }

    private static TopicAddress telemetryAddress() {
        return new TopicAddress(GATEWAY, MqttChannels.TELEMETRY);
    }

    private static TopicAddress address(String gatewayId, MessageType type) {
        return new TopicAddress(gatewayId, ContractExamples.channel(type));
    }

    /** Валидный telemetry.json из контракта с правкой. */
    private static byte[] telemetry(Consumer<ObjectNode> change) {
        ObjectNode json = (ObjectNode) JSON.readTree(
                ContractExamples.dir("valid").resolve("telemetry.json").toFile());
        change.accept(json);
        return JSON.writeValueAsBytes(json);
    }

    private static ObjectNode firstSample(ObjectNode json) {
        return (ObjectNode) ((ArrayNode) json.get("payload").get("samples")).get(0);
    }

    static Stream<Path> validExamples() {
        return ContractExamples.valid().stream();
    }

    static Stream<Path> invalidExamples() {
        return ContractExamples.invalid().stream();
    }
}
