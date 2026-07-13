package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A {@code recurring} row (spec §4), surfaced by the MCP read tools.
 *
 * @param id the {@code recurring.id}
 * @param cadence {@code monthly} | {@code quarterly} | {@code half_yearly} | {@code yearly} |
 *     {@code irregular}
 * @param typicalAmount the representative amount per occurrence
 * @param amountMin smallest observed amount
 * @param amountMax largest observed amount
 * @param firstSeen earliest occurrence of the series
 * @param lastSeen latest occurrence of the series
 * @param occurrenceCount number of occurrences backing this series
 * @param source {@code auto} | {@code confirmed}
 * @param confidence 0..1, {@code null} when not set
 */
public record RecurringView(
    long id,
    String cadence,
    BigDecimal typicalAmount,
    BigDecimal amountMin,
    BigDecimal amountMax,
    LocalDate firstSeen,
    LocalDate lastSeen,
    Integer occurrenceCount,
    String source,
    BigDecimal confidence) {}
