package dev.senso.ingest.validation;

import dev.senso.contracts.mqtt.Envelope;
import tools.jackson.databind.JsonNode;

/**
 * Проверка подписи сообщения шлюза (envelope {@code signature}, с S5 — Ed25519). Вызывается только при
 * {@code senso.ingest.signature-required=true}.
 */
public interface SignatureVerifier {

    /**
     * @param envelope разобранный конверт
     * @param message исходный JSON конверта (подпись считается по каноническому JSON без поля {@code signature})
     */
    boolean verify(Envelope envelope, JsonNode message);
}
