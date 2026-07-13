package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * A {@code counterparty_tags} row (spec §4), surfaced by {@link ReadTools#listCounterparties}.
 *
 * @param dimension {@code domain} | {@code nature} | {@code necessity}
 * @param value the free/emergent tag value
 * @param source {@code auto} | {@code confirmed}
 * @param confidence 0..1, {@code null} when not set
 */
public record CounterpartyTagView(String dimension, String value, String source, BigDecimal confidence) {}
