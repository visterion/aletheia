package de.visterion.aletheia.mcp;

/**
 * Recurring-series cadence (spec §5). Constant names are the exact wire/DB values: Spring AI
 * reflects these into the JSON-schema {@code enum}, and {@link WriteTools#markRecurring} writes
 * {@code Enum::name} straight into {@code recurring.cadence}, which has a matching CHECK
 * constraint.
 */
public enum Cadence {
  monthly,
  quarterly,
  half_yearly,
  yearly,
  irregular
}
