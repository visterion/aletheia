package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.util.List;

/**
 * One row of {@link ReadTools#obligationsRegister} (spec §5, TP1 contract grain): a confirmed
 * {@code contracts} row with its documented annual cost and contract-link status. One row per
 * confirmed contract -- a counterparty with two confirmed contracts (e.g. two Debeka policies)
 * produces two rows, each carrying its OWN annual cost (spec review M1: never the counterparty's
 * combined debit).
 *
 * @param counterpartyId the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param contractId the {@code contracts.id} this row documents
 * @param mandateId the {@code contracts.mandate_id}, {@code null} for a mandate-less obligation
 * @param cadence the {@code recurring.cadence} for this contract's series
 * @param annualCost {@link AnnualCost#estimate(RecurringView, BigDecimal)} for this contract
 * @param tags the counterparty's current {@code counterparty_tags} rows
 * @param hasContract always {@code true} -- every row is rooted at a confirmed contract
 * @param hivememCellId this contract's {@code hivemem_cell_id}, {@code null} if none
 */
public record ObligationRow(
    long counterpartyId,
    String displayName,
    String identityType,
    long contractId,
    String mandateId,
    String cadence,
    BigDecimal annualCost,
    List<CounterpartyTagView> tags,
    boolean hasContract,
    String hivememCellId) {}
