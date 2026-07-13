package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single booking returned by {@link ReadTools#counterpartyTransactions} (spec §5): the raw
 * evidence detail behind a counterparty's aggregates, so the MCP client can inspect the series
 * directly.
 */
public record TransactionView(
    long id,
    LocalDate bookingDate,
    LocalDate valueDate,
    BigDecimal amount,
    String currency,
    String direction,
    String bookingText,
    String remittanceInfo,
    String counterpartyName,
    String counterpartyIban,
    String creditorId) {}
