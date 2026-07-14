package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of {@link ReadTools#listIncome} (spec §5): a counterparty whose predominant direction
 * is credit (CRDT) -- salary, family transfers -- kept out of the obligations queue but still
 * available for cashflow analysis.
 *
 * @param counterpartyId the {@code counterparties.id}
 * @param displayName a representative counterparty name
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param txnCount total bookings matched to this counterparty (both directions)
 * @param creditLast365d {@code v_counterparty_evidence.credit_last_365d}: CRDT-only sum, last 365
 *     days
 * @param creditTotal {@code v_counterparty_evidence.credit_total}: CRDT-only sum, all history
 * @param firstSeen earliest booking date for this counterparty
 * @param lastSeen latest booking date for this counterparty
 */
public record IncomeRow(
    long counterpartyId,
    String displayName,
    String identityType,
    long txnCount,
    BigDecimal creditLast365d,
    BigDecimal creditTotal,
    LocalDate firstSeen,
    LocalDate lastSeen) {}
