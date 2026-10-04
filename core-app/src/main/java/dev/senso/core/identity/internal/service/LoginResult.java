package dev.senso.core.identity.internal.service;

public record LoginResult(
    String accessToken,
    String refreshToken
) {
}
