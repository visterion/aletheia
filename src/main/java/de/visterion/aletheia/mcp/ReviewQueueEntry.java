package de.visterion.aletheia.mcp;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@link ReadTools#getReviewQueue} (spec §5): an open counterparty prioritized by
 * estimated annual cost.
 *
 * <p>Compact (default, {@code verbose=false}): {@code evidence} and {@code recurring} are {@code
 * null}; {@code cadence}, {@code txnCount}, {@code lastSeen} and {@code annualCostEstimate} carry
 * the small summary instead, so a {@code limit=50} listing does not blow up context with the full
 * evidence/recurring blob. Verbose ({@code verbose=true}): {@code evidence} and {@code recurring}
 * are fully populated as before.
 *
 * @param id the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param evidence the {@code v_counterparty_evidence} aggregates; {@code null} in compact mode
 * @param recurring the current {@code recurring} series, {@code null} if none is recorded or in
 *     compact mode
 * @param annualCostEstimate {@code recurring.typical_amount * periods/year} when a non-irregular
 *     recurring series exists, else the DBIT-only spend of the last 365 days ({@code
 *     evidence.debit_last_365d}, spec §5) -- the priority this queue is ordered by, descending
 * @param cadence the recurring series' cadence, {@code null} if none is recorded (populated in
 *     both compact and verbose mode)
 * @param txnCount transaction count from the evidence view, {@code null} if no evidence row
 * @param lastSeen latest booking date from the evidence view, {@code null} if no evidence row
 */
public record ReviewQueueEntry(
    long id,
    String displayName,
    String identityType,
    CounterpartyEvidence evidence,
    RecurringView recurring,
    BigDecimal annualCostEstimate,
    String cadence,
    Integer txnCount,
    LocalDate lastSeen) {}
