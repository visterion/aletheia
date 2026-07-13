package de.visterion.aletheia.mcp;

/**
 * Uniform acknowledgement returned by every {@link WriteTools} method.
 *
 * @param counterpartyId the {@code counterparties.id} the write applied to
 * @param message a short human-readable confirmation
 */
public record WriteAck(long counterpartyId, String message) {}
