package de.visterion.aletheia.mcp;

/** {@code list_counterparties} filter values (spec §5). Default is {@link #all}. */
public enum CounterpartyFilter {
  untagged,
  unreviewed,
  has_recurring,
  all
}
