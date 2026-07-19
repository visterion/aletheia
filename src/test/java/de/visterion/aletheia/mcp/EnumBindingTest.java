package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring context) pinning the exact wire strings of the MCP tool-vocabulary
 * enums. Spring AI reflects the Java enum constant name into the JSON-schema {@code enum} and
 * (for the lowercase ones) that same name is written verbatim to the DB via {@code Enum::name} --
 * so a careless rename here would silently change both the tool contract and the stored data.
 * This test exists so such a rename fails loudly instead.
 */
class EnumBindingTest {

  @Test
  void enumWireValuesAreStable() {
    assertThat(Arrays.stream(Cadence.values()).map(Enum::name))
        .containsExactly("monthly", "quarterly", "half_yearly", "yearly", "irregular");
    assertThat(Arrays.stream(TagSource.values()).map(Enum::name))
        .containsExactly("auto", "confirmed");
    assertThat(Arrays.stream(CounterpartyFilter.values()).map(Enum::name))
        .containsExactly("untagged", "unreviewed", "has_recurring", "all");
    assertThat(Arrays.stream(CounterpartySort.values()).map(Enum::name))
        .containsExactly("spend_desc", "recent");
    assertThat(Arrays.stream(TagDimension.values()).map(Enum::name))
        .containsExactly("domain", "nature", "necessity");
    assertThat(Arrays.stream(Direction.values()).map(Enum::name))
        .containsExactly("DBIT", "CRDT", "BOTH");
    assertThat(Arrays.stream(AggregateMetric.values()).map(Enum::name))
        .containsExactly("SUM", "AVG", "MEDIAN", "COUNT");
    assertThat(Arrays.stream(AggregateGroupBy.values()).map(Enum::name))
        .containsExactly("TOTAL", "MONTH", "QUARTER", "YEAR", "DOMAIN", "NATURE");
  }
}
