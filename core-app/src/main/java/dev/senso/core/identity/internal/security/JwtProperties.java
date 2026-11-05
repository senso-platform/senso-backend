package dev.senso.core.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ключи подписи JWT (PEM). {@code generateIfMissing} включается только в профиле {@code local}: без ключей
 * генерируется временная пара, токены не переживают перезапуск. В api/worker ключи обязательны.
 */
@ConfigurationProperties(prefix = "senso.jwt")
public record JwtProperties(String publicKey, String privateKey, boolean generateIfMissing) {}
