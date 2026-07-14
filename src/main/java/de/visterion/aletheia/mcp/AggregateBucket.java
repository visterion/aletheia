package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * One chart-ready bucket from {@link ReadTools#aggregate}: a period (or {@code "total"}) with the
 * aggregated value, optionally split per counterparty.
 *
 * @param period {@code "total"}, or {@code YYYY-MM-DD} (the bucket's start date) for MONTH/QUARTER/YEAR
 * @param counterpartyId {@code null} unless {@code byCounterparty=true}; the grouping key (never
 *     {@code displayName} -- two distinct identities can share one display name)
 * @param displayName {@code null} unless {@code byCounterparty=true}; a label only, not the group key
 * @param value the aggregated metric value (signing per {@link Direction})
 */
public record AggregateBucket(String period, Long counterpartyId, String displayName, BigDecimal value) {}
