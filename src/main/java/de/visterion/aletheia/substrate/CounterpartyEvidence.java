package de.visterion.aletheia.substrate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A row of the {@code v_counterparty_evidence} view (spec §3): per-counterparty aggregates over
 * {@code transactions}, consumed by the MCP read tools (Task 6). Read-only; never mutates raw
 * data.
 *
 * @param counterpartyId the {@code counterparties.id} this evidence belongs to
 * @param txnCount number of transactions for this counterparty
 * @param firstSeen earliest {@code booking_date}
 * @param lastSeen latest {@code booking_date}
 * @param spanDays days between {@code firstSeen} and {@code lastSeen}
 * @param totalAmount sum of all transaction amounts
 * @param amountMin smallest transaction amount
 * @param amountMax largest transaction amount
 * @param amountAvg average transaction amount
 * @param amountStddev standard deviation of transaction amounts
 * @param medianGapDays median number of days between consecutive transactions (via {@code
 *     LAG()} + {@code percentile_cont(0.5)}, not a flat aggregate); {@code null} when fewer
 *     than two transactions exist (no gap to measure)
 * @param spendLast365d total amount for transactions in the last 365 days (relative to {@code
 *     CURRENT_DATE})
 * @param direction majority booking direction ({@code DBIT} or {@code CRDT})
 */
public record CounterpartyEvidence(
    long counterpartyId,
    int txnCount,
    LocalDate firstSeen,
    LocalDate lastSeen,
    int spanDays,
    BigDecimal totalAmount,
    BigDecimal amountMin,
    BigDecimal amountMax,
    BigDecimal amountAvg,
    BigDecimal amountStddev,
    Double medianGapDays,
    BigDecimal spendLast365d,
    String direction) {}
