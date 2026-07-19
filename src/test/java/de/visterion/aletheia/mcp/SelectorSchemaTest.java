package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the hand-rolled {@link CounterpartySelectorSchema#where()} JSON-Schema fragment for
 * {@link CounterpartySelector}: none of its 15 fields are marked required (a partial {@code where}
 * filter -- only some fields set -- must be a valid payload for the {@code aggregate}, {@code
 * classify_counterparty}, {@code dismiss_counterparty}, and {@code confirm_counterparty} tools),
 * and {@code predominantDirection} carries the correct enum values.
 *
 * <p>Rewritten for Task 10 (Spring AI removal): the previous version generated the schema via
 * Spring AI's {@code JsonSchemaGenerator} reflecting over {@link CounterpartySelector}; the schema
 * is now hand-authored in {@link CounterpartySelectorSchema}, so this test asserts its output
 * directly instead.
 */
class SelectorSchemaTest {

  private static final List<String> EXPECTED_PROPERTIES =
      List.of(
          "untagged",
          "namePattern",
          "minAnnualCost",
          "predominantDirection",
          "domainIn",
          "natureIn",
          "reviewed",
          "hasContract",
          "txnCountMax",
          "natureNotIn",
          "domainNotIn",
          "amountMin",
          "amountMax",
          "lastSeenBefore",
          "lastSeenAfter");

  @Test
  @SuppressWarnings("unchecked")
  void allSelectorFieldsAreOptionalInGeneratedSchema() {
    Map<String, Object> schema = CounterpartySelectorSchema.where().build();

    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).as("schema properties").isNotNull();
    assertThat(properties.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_PROPERTIES);

    // ToolInputSchema omits the "required" key entirely when no field is required (its javadoc).
    assertThat(schema).as("required fields").doesNotContainKey("required");
  }

  @Test
  @SuppressWarnings("unchecked")
  void predominantDirectionCarriesTheExpectedEnumValues() {
    Map<String, Object> schema = CounterpartySelectorSchema.where().build();
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    Map<String, Object> predominantDirection = (Map<String, Object>) properties.get("predominantDirection");

    assertThat(predominantDirection.get("type")).isEqualTo("string");
    assertThat((List<String>) predominantDirection.get("enum"))
        .containsExactly("DBIT", "CRDT", "BOTH");
  }
}
