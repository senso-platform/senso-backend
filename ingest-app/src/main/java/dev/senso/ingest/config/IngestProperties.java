package dev.senso.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Границы приёмного фильтра (ADR-0001 §6, contracts/mqtt/TOPICS.md «Ограничения»).
 * Значения по умолчанию — в application.yml.
 */
@ConfigurationProperties(prefix = "senso.ingest")
public record IngestProperties(
        DataSize maxMessageSize, Duration maxFutureSkew, Duration confirmTimeout, boolean signatureRequired) {}
