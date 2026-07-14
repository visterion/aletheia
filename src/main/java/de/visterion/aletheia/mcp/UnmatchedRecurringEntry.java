package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * One row of {@link ReadTools#listUnmatchedRecurring} (spec §5, reworked TP1 Task 6): a
 * recurring debit without a documented contract. Two shapes, both mapped here: (1) an unlinked
 * mandate contract -- a {@code contracts} row with no {@code hivemem_cell_id} yet, joined to its
 * {@code recurring} series ({@code contractId} set); (2) a mandate-less auto series -- a {@code
 * recurring} row with no {@code contract_id} at all ({@code contractId} {@code null}).
 *
 * @param counterpartyId the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param identityValue the resolved identity key
 * @param contractId the {@code contracts.id}, {@code null} for a mandate-less series
 * @param recurring the unmatched {@code recurring} series
 * @param annualCostEstimate {@link AnnualCost#estimate}, scoped to the contract when one exists
 */
public record UnmatchedRecurringEntry(
    long counterpartyId,
    String displayName,
    String identityType,
    String identityValue,
    Long contractId,
    RecurringView recurring,
    BigDecimal annualCostEstimate) {}
