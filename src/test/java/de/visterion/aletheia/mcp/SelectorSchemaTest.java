package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies that the generated MCP tool-parameter schema for {@link CounterpartySelector} does not
 * mark any of its 8 fields as required. A partial {@code where} filter (only some fields set) must
 * be a valid payload for the {@code aggregate}, {@code classify_counterparty},
 * {@code dismiss_counterparty}, and {@code confirm_counterparty} tools.
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
          "hasContract");

  @Test
  void allSelectorFieldsAreOptionalInGeneratedSchema() throws Exception {
    String schemaJson = JsonSchemaGenerator.generateForType(CounterpartySelector.class);

    ObjectMapper mapper = new ObjectMapper();
    JsonNode schema = mapper.readTree(schemaJson);

    JsonNode properties = schema.get("properties");
    assertThat(properties).as("schema properties").isNotNull();
    List<String> propertyNames = new ArrayList<>();
    properties.propertyNames().forEach(propertyNames::add);
    assertThat(propertyNames).containsExactlyInAnyOrderElementsOf(EXPECTED_PROPERTIES);

    JsonNode required = schema.get("required");
    List<String> requiredNames =
        required == null
            ? List.of()
            : StreamSupport.stream(required.spliterator(), false).map(JsonNode::asText).toList();
    assertThat(requiredNames).as("required fields").isEmpty();
  }
}
