package dev.senso.contracts.mqtt;

/** Message signature; null in MVP, filled from S5 (ADR-0001 §6.2). */
public record Signature(String alg, String kid, String value) {}
