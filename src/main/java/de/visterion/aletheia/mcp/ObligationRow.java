package de.visterion.aletheia.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * One row of {@link ReadTools#obligationsRegister} (spec §5, TP1 contract grain): a confirmed
 * {@code contracts} row with its documented annual cost and contract-link status. One row per
 * confirmed contract -- a counterparty with two confirmed contracts (e.g. two policies with the
 * same insurer)
 * produces two rows, each carrying its OWN annual cost (spec review M1: never the counterparty's
 * combined debit).
 *
 * @param counterpartyId the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}; {@code null} in compact
 *     mode, where the key is omitted entirely
 * @param contractId the {@code contracts.id} this row documents
 * @param mandateId the {@code contracts.mandate_id}, {@code null} for a mandate-less obligation
 *     (verbose mode only; the key is omitted entirely in compact mode)
 * @param product the {@code contracts.product} this row documents, {@code null} (and omitted from
 *     the JSON) for a mandate whose creditor has no product rule -- one SEPA mandate that bundles
 *     several products yields one confirmed contract per product, each with its own annual cost
 * @param cadence the {@code recurring.cadence} for this contract's series
 * @param annualCost {@link AnnualCost#estimate(RecurringView, BigDecimal)} for this contract
 * @param tags the counterparty's current {@code counterparty_tags} rows (verbose mode only; the
 *     key is omitted entirely in compact mode)
 * @param hasContract always {@code true} in verbose mode; {@code null} in compact mode, where the
 *     key is omitted entirely
 * @param hivememCellId this contract's {@code hivemem_cell_id}, {@code null} if none (verbose mode
 *     only; the key is omitted entirely in compact mode)
 */
// Null-valued fields are omitted from the JSON entirely: in compact mode identityType, mandateId,
// tags, hasContract and hivememCellId are passed as null and therefore disappear, rather than
// emitting empty/explicit-null keys.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObligationRow(
    long counterpartyId,
    String displayName,
    String identityType,
    long contractId,
    String mandateId,
    String product,
    String cadence,
    BigDecimal annualCost,
    List<CounterpartyTagView> tags,
    Boolean hasContract,
    String hivememCellId) {}
