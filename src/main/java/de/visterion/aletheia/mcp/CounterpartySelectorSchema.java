package de.visterion.aletheia.mcp;

/**
 * Shared JSON-Schema fragment for the declarative {@code where}-selector ({@link
 * CounterpartySelector}), reused by every tool handler that accepts one (e.g. {@code aggregate}).
 * Field names and the {@code predominantDirection} enum values match {@link CounterpartySelector}
 * exactly.
 */
public final class CounterpartySelectorSchema {

  private CounterpartySelectorSchema() {}

  public static ToolInputSchema where() {
    return ToolInputSchema.object()
        .optionalStringList("domainIn", "matches when a counterparty_tags(dimension='domain') value is in this list")
        .optionalStringList("natureIn", "matches when a counterparty_tags(dimension='nature') value is in this list")
        .optionalDecimal("minAnnualCost", "minimum estimated annual cost")
        .optionalString("namePattern", "case-insensitive substring match against display_name")
        .optionalEnumString(
            "predominantDirection",
            "v_counterparty_evidence.direction; BOTH is rejected",
            "DBIT",
            "CRDT",
            "BOTH")
        .optionalBoolean("reviewed", "matches counterparties.reviewed")
        .optionalBoolean("hasContract", "whether any contracts row exists for the counterparty")
        .optionalBoolean("untagged", "true to require no rows in counterparty_tags");
  }
}
