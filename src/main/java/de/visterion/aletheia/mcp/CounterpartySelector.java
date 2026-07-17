package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.util.List;

/**
 * A declarative {@code where}-selector for counterparties, shared by the {@code aggregate} and
 * batch {@code classify_counterparty} tools. Every field is nullable and means "no filter on this
 * dimension" when {@code null}.
 *
 * @param untagged {@code true} to require no rows in {@code counterparty_tags}
 * @param namePattern case-insensitive substring match against {@code counterparties.display_name}
 * @param minAnnualCost minimum {@link AnnualCost#estimate}, evaluated in Java (not a column)
 * @param predominantDirection {@code v_counterparty_evidence.direction}; {@code BOTH} is rejected
 * @param domainIn matches when a {@code counterparty_tags} row with {@code dimension = 'domain'}
 *     has a {@code value} in this list; an empty (non-null) list is rejected as ambiguous
 * @param natureIn matches when a {@code counterparty_tags} row with {@code dimension = 'nature'}
 *     has a {@code value} in this list; an empty (non-null) list is rejected as ambiguous
 * @param reviewed matches {@code counterparties.reviewed}
 * @param hasContract {@code true}/{@code false} for whether any {@code contracts} row exists for
 *     the counterparty
 */
public record CounterpartySelector(
    Boolean untagged,
    String namePattern,
    BigDecimal minAnnualCost,
    Direction predominantDirection,
    List<String> domainIn,
    List<String> natureIn,
    Boolean reviewed,
    Boolean hasContract) {}
