package de.visterion.aletheia.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A declarative {@code where}-selector for counterparties, shared by the {@code aggregate} and
 * batch {@code classify_counterparty} tools. Every field is nullable and means "no filter on this
 * dimension" when {@code null}.
 *
 * <p>Each component is annotated {@code @JsonProperty(required = false)} so the generated MCP tool
 * schema does not mark it as required; Spring AI's {@code JsonSchemaGenerator} otherwise defaults
 * every record component to required, which rejects a caller that omits fields it doesn't want to
 * filter on (the common case for a partial {@code where} selector).
 *
 * @param untagged {@code true} to require no rows in {@code counterparty_tags}
 * @param namePattern case-insensitive substring match against the effective name (the
 *     override-first {@code COALESCE(display_name_override, display_name)})
 * @param minAnnualCost minimum {@link AnnualCost#estimate}, evaluated in Java (not a column)
 * @param predominantDirection {@code v_counterparty_evidence.direction}; {@code BOTH} is rejected
 * @param domainIn matches when a {@code counterparty_tags} row with {@code dimension = 'domain'}
 *     has a {@code value} in this list; an empty (non-null) list is rejected as ambiguous
 * @param natureIn matches when a {@code counterparty_tags} row with {@code dimension = 'nature'}
 *     has a {@code value} in this list; an empty (non-null) list is rejected as ambiguous
 * @param reviewed matches {@code counterparties.reviewed}
 * @param hasContract {@code true}/{@code false} for whether any {@code contracts} row exists for
 *     the counterparty
 * @param txnCountMax matches when the logical booking count ({@code v_counterparty_evidence.txn_count},
 *     split parents excluded) is &le; this value; a no-evidence counterparty (count 0) matches
 * @param natureNotIn matches when the counterparty has NO {@code nature} tag whose value is in this
 *     list (untagged passes); an empty (non-null) list is rejected as ambiguous
 * @param domainNotIn matches when the counterparty has NO {@code domain} tag whose value is in this
 *     list (untagged passes); an empty (non-null) list is rejected as ambiguous
 * @param amountMin matches when {@code v_counterparty_evidence.amount_max} (= max(abs(booking)),
 *     credits included) is &ge; this value; a no-evidence counterparty is excluded
 * @param amountMax matches when {@code amount_max} is &le; this value; a no-evidence counterparty
 *     is excluded
 * @param lastSeenBefore matches when {@code v_counterparty_evidence.last_seen} is &le; this date
 *     (inclusive); a no-evidence counterparty is excluded
 * @param lastSeenAfter matches when {@code last_seen} is &ge; this date (inclusive); a no-evidence
 *     counterparty is excluded
 */
public record CounterpartySelector(
    @JsonProperty(required = false) Boolean untagged,
    @JsonProperty(required = false) String namePattern,
    @JsonProperty(required = false) BigDecimal minAnnualCost,
    @JsonProperty(required = false) Direction predominantDirection,
    @JsonProperty(required = false) List<String> domainIn,
    @JsonProperty(required = false) List<String> natureIn,
    @JsonProperty(required = false) Boolean reviewed,
    @JsonProperty(required = false) Boolean hasContract,
    @JsonProperty(required = false) Long txnCountMax,
    @JsonProperty(required = false) List<String> natureNotIn,
    @JsonProperty(required = false) List<String> domainNotIn,
    @JsonProperty(required = false) BigDecimal amountMin,
    @JsonProperty(required = false) BigDecimal amountMax,
    @JsonProperty(required = false) LocalDate lastSeenBefore,
    @JsonProperty(required = false) LocalDate lastSeenAfter) {}
