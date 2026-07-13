package de.visterion.aletheia.mcp;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.math.BigDecimal;

/**
 * One row of {@link ReadTools#getReviewQueue} (spec §5): an open counterparty prioritized by
 * estimated annual cost.
 *
 * @param id the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param evidence the {@code v_counterparty_evidence} aggregates
 * @param recurring the current {@code recurring} series, {@code null} if none is recorded
 * @param annualCostEstimate {@code recurring.typical_amount * periods/year} when a non-irregular
 *     recurring series exists, else {@code evidence.spend_last_365d} (spec §5) -- the priority
 *     this queue is ordered by, descending
 */
public record ReviewQueueEntry(
    long id,
    String displayName,
    String identityType,
    CounterpartyEvidence evidence,
    RecurringView recurring,
    BigDecimal annualCostEstimate) {}
