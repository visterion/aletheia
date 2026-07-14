package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * A declarative {@code where}-selector for counterparties, shared by the {@code aggregate} and
 * batch {@code classify_counterparty} tools. Every field is nullable and means "no filter on this
 * dimension" when {@code null}.
 *
 * @param untagged {@code true} to require no rows in {@code counterparty_tags}
 * @param namePattern case-insensitive substring match against {@code counterparties.display_name}
 * @param minAnnualCost minimum {@link AnnualCost#estimate}, evaluated in Java (not a column)
 * @param predominantDirection {@code v_counterparty_evidence.direction}; {@code BOTH} is rejected
 */
public record CounterpartySelector(
    Boolean untagged, String namePattern, BigDecimal minAnnualCost, Direction predominantDirection) {}
