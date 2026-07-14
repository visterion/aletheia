package de.visterion.aletheia.mcp;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.util.List;

/**
 * One row of {@link ReadTools#listCounterparties} (spec §5): a counterparty joined to its
 * evidence aggregates, current tags, recurring series (if any) and whether it already has a
 * linked {@code contracts} row.
 *
 * @param id the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param identityValue the resolved identity key
 * @param status {@code open} | {@code confirmed} | {@code dismissed}
 * @param reviewed whether a human has reviewed this counterparty
 * @param evidence the {@code v_counterparty_evidence} aggregates, {@code null} if the
 *     counterparty has no transactions matched (should not normally happen)
 * @param tags the current {@code counterparty_tags}
 * @param recurring a representative {@code recurring} series (the first one seen when the
 *     counterparty has several, TP1 -- one per contract), {@code null} if none is recorded
 * @param hasContract whether a {@code contracts} row links this counterparty to HiveMem
 * @param contractCount the number of {@code contracts} rows for this counterparty (TP1 -- a
 *     counterparty can have more than one, e.g. two insurance policies with the same insurer)
 */
public record CounterpartySummary(
    long id,
    String displayName,
    String identityType,
    String identityValue,
    String status,
    boolean reviewed,
    CounterpartyEvidence evidence,
    List<CounterpartyTagView> tags,
    RecurringView recurring,
    boolean hasContract,
    int contractCount) {}
