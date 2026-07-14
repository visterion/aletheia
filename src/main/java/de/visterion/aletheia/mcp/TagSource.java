package de.visterion.aletheia.mcp;

/**
 * Provenance of a tag / recurring-series row (spec §5): {@code auto} (a rule/LLM proposal, never
 * counts as reviewed) vs. {@code confirmed} (a human decision). Constant names are the exact
 * wire/DB values written via {@code Enum::name}.
 */
public enum TagSource {
  auto,
  confirmed
}
