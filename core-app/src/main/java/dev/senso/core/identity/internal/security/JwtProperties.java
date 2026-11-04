package dev.senso.core.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "senso.jwt")
public record JwtProperties(String publicKey, String privateKey) {}
