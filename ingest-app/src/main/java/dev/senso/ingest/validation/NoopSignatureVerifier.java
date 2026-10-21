package dev.senso.ingest.validation;

import dev.senso.contracts.mqtt.Envelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Заглушка MVP: подпись не проверяется (AGENTS.md §1). Настоящая реализация — S5. */
@Component
public class NoopSignatureVerifier implements SignatureVerifier {

    @Override
    public boolean verify(Envelope envelope, JsonNode message) {
        return true;
    }
}
