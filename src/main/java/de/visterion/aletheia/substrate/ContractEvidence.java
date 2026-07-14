package de.visterion.aletheia.substrate;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Mirrors {@code v_contract_evidence}: per-(counterparty, mandate) evidence for a contract. */
public record ContractEvidence(
    long counterpartyId,
    String mandateId,
    Integer txnCount,
    LocalDate firstSeen,
    LocalDate lastSeen,
    BigDecimal medianGapDays,
    BigDecimal amountMin,
    BigDecimal amountMax,
    BigDecimal amountAvg,
    BigDecimal debitLast365d) {}
