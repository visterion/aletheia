package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link ToolInputSchema} JSON-Schema DSL. */
class ToolInputSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void requiredStringAndOptionalIntegerProduceExpectedSchema() {
    Map<String, Object> schema =
        ToolInputSchema.object().requiredString("a", "d").optionalInteger("b", "d").build();

    assertThat(schema).containsEntry("type", "object");
    assertThat(schema).containsEntry("additionalProperties", false);
    assertThat(schema).containsEntry("required", List.of("a"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsEntry("a", Map.of("type", "string", "description", "d"));
    assertThat(properties).containsEntry("b", Map.of("type", "integer", "description", "d"));
  }

  @Test
  void optionalEnumStringAppendsEnumHintToDescription() {
    Map<String, Object> schema = ToolInputSchema.object().optionalEnumString("s", "d", "X", "Y").build();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> prop = (Map<String, Object>) properties.get("s");

    assertThat(prop).containsEntry("type", "string");
    assertThat(prop).containsEntry("enum", List.of("X", "Y"));
    assertThat(prop).containsEntry("description", "d. One of: X, Y");
    assertThat(prop).doesNotContainKey("format");
  }

  @Test
  void requiredObjectListInlinesItemSchemaAndMarksParentRequired() {
    Map<String, Object> schema =
        ToolInputSchema.object()
            .requiredObjectList(
                "refs",
                "d",
                ToolInputSchema.object().requiredString("h", "").requiredInteger("i", ""))
            .build();

    assertThat(schema).containsEntry("required", List.of("refs"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> refsProp = (Map<String, Object>) properties.get("refs");

    assertThat(refsProp).containsEntry("type", "array");
    assertThat(refsProp).containsEntry("description", "d");

    @SuppressWarnings("unchecked")
    Map<String, Object> items = (Map<String, Object>) refsProp.get("items");
    assertThat(items).containsEntry("type", "object");
    assertThat(items).containsEntry("additionalProperties", false);
    assertThat(items).containsEntry("required", List.of("h", "i"));

    @SuppressWarnings("unchecked")
    Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
    assertThat(itemProperties).containsKeys("h", "i");
  }

  @Test
  void optionalDateEmitsBareStringWithoutFormat() {
    Map<String, Object> schema = ToolInputSchema.object().optionalDate("d", "").build();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsEntry("d", Map.of("type", "string"));
  }

  @Test
  void requiredDateEmitsBareStringWithoutFormat() {
    Map<String, Object> schema = ToolInputSchema.object().requiredDate("d", "").build();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsEntry("d", Map.of("type", "string"));
    assertThat(schema).containsEntry("required", List.of("d"));
  }

  @Test
  void decimalAndLongScalarsEmitNumberAndIntegerWithoutFormat() {
    Map<String, Object> schema =
        ToolInputSchema.object()
            .optionalDecimal("dec", "")
            .optionalLong("lng", "")
            .requiredDecimal("rdec", "")
            .requiredLong("rlng", "")
            .build();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsEntry("dec", Map.of("type", "number"));
    assertThat(properties).containsEntry("lng", Map.of("type", "integer"));
    assertThat(properties).containsEntry("rdec", Map.of("type", "number"));
    assertThat(properties).containsEntry("rlng", Map.of("type", "integer"));
    assertThat(schema).containsEntry("required", List.of("rdec", "rlng"));
  }

  @Test
  void optionalLongListEmitsIntegerArray() {
    Map<String, Object> schema = ToolInputSchema.object().optionalLongList("ids", "").build();

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> prop = (Map<String, Object>) properties.get("ids");
    assertThat(prop).containsEntry("type", "array");
    assertThat(prop).containsEntry("items", Map.of("type", "integer"));
  }

  @Test
  void requiredObjectAppendsToRequiredList() {
    Map<String, Object> schema =
        ToolInputSchema.object()
            .requiredObject("o", "d", ToolInputSchema.object().requiredString("x", ""))
            .build();

    assertThat(schema).containsEntry("required", List.of("o"));

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> oProp = (Map<String, Object>) properties.get("o");
    assertThat(oProp).containsEntry("type", "object");
    assertThat(oProp).containsEntry("additionalProperties", false);
    assertThat(oProp).containsEntry("required", List.of("x"));
  }

  @Test
  void optionalObjectListDoesNotAppendToRequired() {
    Map<String, Object> schema =
        ToolInputSchema.object()
            .optionalObjectList("items", "d", ToolInputSchema.object().optionalString("x", ""))
            .build();

    assertThat(schema).doesNotContainKey("required");
  }

  @Test
  void emptySchemaHasNoRequiredKey() {
    Map<String, Object> schema = ToolInputSchema.empty();

    assertThat(schema).containsEntry("type", "object");
    assertThat(schema).containsEntry("properties", Map.of());
    assertThat(schema).containsEntry("additionalProperties", false);
    assertThat(schema).doesNotContainKey("required");
  }

  @Test
  void fullSchemaUsingEveryBuilderNeverEmitsForbiddenKeywordsOrFormat() throws Exception {
    Map<String, Object> schema =
        ToolInputSchema.object()
            .requiredString("str", "d")
            .optionalString("optStr", "d")
            .requiredEnumString("enumStr", "d", "A", "B")
            .optionalEnumString("optEnumStr", "d", "A", "B")
            .requiredInteger("intg", "d")
            .optionalInteger("optIntg", "d")
            .requiredNumber("num", "d")
            .optionalNumber("optNum", "d")
            .requiredDecimal("dec", "d")
            .optionalDecimal("optDec", "d")
            .optionalBoolean("bool", "d")
            .requiredLong("lng", "d")
            .optionalLong("optLng", "d")
            .optionalLongList("lngList", "d")
            .requiredDate("date", "d")
            .optionalDate("optDate", "d")
            .requiredStringList("strList", "d")
            .optionalStringList("optStrList", "d")
            .requiredEnumStringList("enumStrList", "d", "A", "B")
            .optionalEnumStringList("optEnumStrList", "d", "A", "B")
            .requiredObject("obj", "d", ToolInputSchema.object().requiredString("x", ""))
            .optionalObject("optObj", "d", ToolInputSchema.object().optionalString("x", ""))
            .requiredObjectList(
                "objList", "d", ToolInputSchema.object().requiredString("x", ""))
            .optionalObjectList(
                "optObjList", "d", ToolInputSchema.object().optionalString("x", ""))
            .build();

    String json = MAPPER.writeValueAsString(schema);

    assertThat(json).doesNotContain("$ref");
    assertThat(json).doesNotContain("$defs");
    assertThat(json).doesNotContain("oneOf");
    assertThat(json).doesNotContain("anyOf");
    assertThat(json).doesNotContain("allOf");
    assertThat(json).doesNotContain("\"format\"");
    assertThat(json).doesNotContain("minimum");
    assertThat(json).doesNotContain("maximum");
    assertThat(json).doesNotContain("minItems");
    assertThat(json).doesNotContain("maxItems");

    assertAllObjectNodesHaveAdditionalPropertiesFalse(schema);
    assertNoEmptyRequiredKey(schema);
  }

  @SuppressWarnings("unchecked")
  private static void assertAllObjectNodesHaveAdditionalPropertiesFalse(Object node) {
    if (node instanceof Map<?, ?> map) {
      if ("object".equals(map.get("type"))) {
        assertThat(map.get("additionalProperties")).isEqualTo(false);
      }
      for (Object value : map.values()) {
        assertAllObjectNodesHaveAdditionalPropertiesFalse(value);
      }
    } else if (node instanceof List<?> list) {
      for (Object value : list) {
        assertAllObjectNodesHaveAdditionalPropertiesFalse(value);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void assertNoEmptyRequiredKey(Object node) {
    if (node instanceof Map<?, ?> map) {
      if (map.containsKey("required")) {
        List<?> required = (List<?>) map.get("required");
        assertThat(required).isNotEmpty();
      }
      for (Object value : map.values()) {
        assertNoEmptyRequiredKey(value);
      }
    } else if (node instanceof List<?> list) {
      for (Object value : list) {
        assertNoEmptyRequiredKey(value);
      }
    }
  }
}
