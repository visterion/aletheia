package de.visterion.aletheia.mcp;

/**
 * A {@code counterparty_alias} row pooled under this counterparty (sub-project A/P1 counterparty
 * merge), surfaced by {@link ReadTools#listCounterparties}.
 *
 * @param identityType {@code creditor_id} | {@code iban} | {@code name}
 * @param identityValue the folded source identity's resolved identity key
 */
public record CounterpartyAliasView(String identityType, String identityValue) {}
