package de.visterion.aletheia.tagrules;

/** Transaction field a rule condition matches against (spec §3). Constant names are the JSON/wire values. */
public enum RuleField {
  remittance_info,
  counterparty_name,
  creditor_id,
  counterparty_iban,
  direction
}
