package de.visterion.aletheia.mcp;

/**
 * One row of {@link ReadTools#listUnmatchedRecurring} (spec §5): a {@code recurring} series whose
 * counterparty has no {@code contracts} row -- the core cross-check ("recurring debit without a
 * documented contract").
 *
 * @param counterpartyId the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param identityValue the resolved identity key
 * @param recurring the unmatched {@code recurring} series
 */
public record UnmatchedRecurringEntry(
    long counterpartyId,
    String displayName,
    String identityType,
    String identityValue,
    RecurringView recurring) {}
