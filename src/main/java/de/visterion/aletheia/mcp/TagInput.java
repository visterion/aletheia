package de.visterion.aletheia.mcp;

/**
 * One {@code {dimension, value}} pair in {@link WriteTools#classifyCounterparty}'s {@code tags}
 * argument (spec §5 "Write").
 *
 * @param dimension {@code domain} | {@code nature} | {@code necessity}
 * @param value the free/emergent tag value
 */
public record TagInput(String dimension, String value) {}
