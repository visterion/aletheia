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
    // The Spring AI oracle (Task 9 differential parity IT) emits no per-field description for
    // these nested where-selector properties (no @JsonPropertyDescription on CounterpartySelector's
    // record components) -- so none are set here either, for verbatim parity.
    return ToolInputSchema.object()
        .optionalStringList("domainIn", "")
        .optionalStringList("natureIn", "")
        .optionalDecimal("minAnnualCost", "")
        .optionalString("namePattern", "")
        .optionalEnumString("predominantDirection", "", "DBIT", "CRDT", "BOTH")
        .optionalBoolean("reviewed", "")
        .optionalBoolean("hasContract", "")
        .optionalBoolean("untagged", "");
  }
}
