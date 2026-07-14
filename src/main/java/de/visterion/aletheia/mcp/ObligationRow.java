package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.util.List;

/**
 * One row of {@link ReadTools#obligationsRegister} (spec §5): a confirmed, predominantly
 * outgoing (DBIT) recurring counterparty, with its documented annual cost and contract-link
 * status.
 *
 * @param counterpartyId the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param cadence the {@code recurring.cadence} for this counterparty's series
 * @param annualCost {@link AnnualCost#estimate} for this counterparty
 * @param tags the counterparty's current {@code counterparty_tags} rows
 * @param hasContract whether a {@code contracts} row is linked to this counterparty
 * @param hivememCellId the linked contract's {@code hivemem_cell_id}, {@code null} if none
 */
public record ObligationRow(
    long counterpartyId,
    String displayName,
    String identityType,
    String cadence,
    BigDecimal annualCost,
    List<CounterpartyTagView> tags,
    boolean hasContract,
    String hivememCellId) {}
