package de.visterion.aletheia.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for JSON-Schema input descriptors attached to MCP tool {@code inputSchema()}.
 *
 * <p>MCP clients rely on {@code tools/list} responses to know which arguments a tool accepts.
 * {@code ToolInputSchema} lets each tool handler describe its own arguments in a readable,
 * type-safe way, producing plain {@code Map<String, Object>} JSON-Schema fragments.
 *
 * <p>Deliberately restricted to the flat subset of JSON-Schema the captured prod contract uses:
 * no {@code format}, no {@code $ref}/{@code $defs}/{@code oneOf}/{@code anyOf}/{@code allOf}, and
 * no {@code minimum}/{@code maximum}/{@code minItems}/{@code maxItems}. Date fields are emitted as
 * bare {@code {"type":"string"}} — {@code requiredDate}/{@code optionalDate} exist only for
 * naming/parser-typing clarity at call sites.
 *
 * <p>Output shape:
 *
 * <pre>{@code
 * {
 *   "type": "object",
 *   "properties": { ... },
 *   "required": [ ... ],         // omitted when no field is required
 *   "additionalProperties": false
 * }
 * }</pre>
 */
public final class ToolInputSchema {

  private final Map<String, Object> properties = new LinkedHashMap<>();
  private final List<String> required = new ArrayList<>();

  private ToolInputSchema() {}

  public static ToolInputSchema object() {
    return new ToolInputSchema();
  }

  /** Immutable empty schema — use for tools that take no arguments. */
  public static Map<String, Object> empty() {
    return schemaOf(Map.of(), List.of());
  }

  public ToolInputSchema requiredString(String name, String description) {
    return addScalar(name, "string", description, true);
  }

  public ToolInputSchema optionalString(String name, String description) {
    return addScalar(name, "string", description, false);
  }

  public ToolInputSchema requiredEnumString(String name, String description, String... values) {
    return addEnumString(name, description, values, true);
  }

  public ToolInputSchema optionalEnumString(String name, String description, String... values) {
    return addEnumString(name, description, values, false);
  }

  public ToolInputSchema requiredInteger(String name, String description) {
    return addScalar(name, "integer", description, true);
  }

  public ToolInputSchema optionalInteger(String name, String description) {
    return addScalar(name, "integer", description, false);
  }

  public ToolInputSchema requiredNumber(String name, String description) {
    return addScalar(name, "number", description, true);
  }

  public ToolInputSchema optionalNumber(String name, String description) {
    return addScalar(name, "number", description, false);
  }

  public ToolInputSchema requiredDecimal(String name, String description) {
    return addScalar(name, "number", description, true);
  }

  public ToolInputSchema optionalDecimal(String name, String description) {
    return addScalar(name, "number", description, false);
  }

  public ToolInputSchema requiredLong(String name, String description) {
    return addScalar(name, "integer", description, true);
  }

  public ToolInputSchema optionalLong(String name, String description) {
    return addScalar(name, "integer", description, false);
  }

  public ToolInputSchema optionalLongList(String name, String description) {
    return addArray(name, "integer", description, false);
  }

  public ToolInputSchema requiredDate(String name, String description) {
    return addScalar(name, "string", description, true);
  }

  public ToolInputSchema optionalDate(String name, String description) {
    return addScalar(name, "string", description, false);
  }

  public ToolInputSchema requiredStringList(String name, String description) {
    return addArray(name, "string", description, true);
  }

  public ToolInputSchema optionalStringList(String name, String description) {
    return addArray(name, "string", description, false);
  }

  public ToolInputSchema requiredEnumStringList(String name, String description, String... values) {
    return addEnumArray(name, description, values, true);
  }

  public ToolInputSchema optionalEnumStringList(String name, String description, String... values) {
    return addEnumArray(name, description, values, false);
  }

  public ToolInputSchema optionalBoolean(String name, String description) {
    return addScalar(name, "boolean", description, false);
  }

  public ToolInputSchema requiredObject(String name, String description, ToolInputSchema nested) {
    return addObject(name, description, nested, true);
  }

  public ToolInputSchema optionalObject(String name, String description, ToolInputSchema nested) {
    return addObject(name, description, nested, false);
  }

  public ToolInputSchema requiredObjectList(String name, String description, ToolInputSchema itemSchema) {
    return addObjectArray(name, description, itemSchema, true);
  }

  public ToolInputSchema optionalObjectList(String name, String description, ToolInputSchema itemSchema) {
    return addObjectArray(name, description, itemSchema, false);
  }

  public Map<String, Object> build() {
    return schemaOf(Map.copyOf(properties), List.copyOf(required));
  }

  private ToolInputSchema addScalar(String name, String type, String description, boolean isRequired) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", type);
    if (description != null && !description.isBlank()) {
      prop.put("description", description);
    }
    properties.put(name, Map.copyOf(prop));
    if (isRequired) {
      required.add(name);
    }
    return this;
  }

  private ToolInputSchema addEnumString(String name, String description, String[] values, boolean isRequired) {
    if (values == null || values.length == 0) {
      throw new IllegalArgumentException("enum values must not be empty for property '" + name + "'");
    }
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "string");
    prop.put("enum", List.of(values));
    String mergedDescription = appendEnumHint(description, values);
    if (!mergedDescription.isBlank()) {
      prop.put("description", mergedDescription);
    }
    properties.put(name, Map.copyOf(prop));
    if (isRequired) {
      required.add(name);
    }
    return this;
  }

  private static String appendEnumHint(String description, String[] values) {
    String hint = "One of: " + String.join(", ", values);
    if (description == null || description.isBlank()) {
      return hint;
    }
    return description + ". " + hint;
  }

  private ToolInputSchema addArray(String name, String itemType, String description, boolean isRequired) {
    Map<String, Object> itemSchema = new LinkedHashMap<>();
    itemSchema.put("type", itemType);
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "array");
    prop.put("items", Map.copyOf(itemSchema));
    if (description != null && !description.isBlank()) {
      prop.put("description", description);
    }
    properties.put(name, Map.copyOf(prop));
    if (isRequired) {
      required.add(name);
    }
    return this;
  }

  private ToolInputSchema addEnumArray(String name, String description, String[] values, boolean isRequired) {
    if (values == null || values.length == 0) {
      throw new IllegalArgumentException("enum values must not be empty for property '" + name + "'");
    }
    Map<String, Object> itemSchema = new LinkedHashMap<>();
    itemSchema.put("type", "string");
    itemSchema.put("enum", List.of(values));
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "array");
    prop.put("items", Map.copyOf(itemSchema));
    String mergedDescription = appendEnumHint(description, values);
    if (!mergedDescription.isBlank()) {
      prop.put("description", mergedDescription);
    }
    properties.put(name, Map.copyOf(prop));
    if (isRequired) {
      required.add(name);
    }
    return this;
  }

  private ToolInputSchema addObject(String name, String description, ToolInputSchema nested, boolean isRequired) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.putAll(objectSchemaOf(nested));
    if (description != null && !description.isBlank()) {
      prop.put("description", description);
    }
    properties.put(name, Map.copyOf(prop));
    if (isRequired) {
      required.add(name);
    }
    return this;
  }

  private ToolInputSchema addObjectArray(
      String name, String description, ToolInputSchema itemSchema, boolean isRequired) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "array");
    prop.put("items", objectSchemaOf(itemSchema));
    if (description != null && !description.isBlank()) {
      prop.put("description", description);
    }
    properties.put(name, Map.copyOf(prop));
    if (isRequired) {
      required.add(name);
    }
    return this;
  }

  private static Map<String, Object> objectSchemaOf(ToolInputSchema nested) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.copyOf(nested.properties));
    if (!nested.required.isEmpty()) {
      schema.put("required", List.copyOf(nested.required));
    }
    schema.put("additionalProperties", Boolean.FALSE);
    return Map.copyOf(schema);
  }

  private static Map<String, Object> schemaOf(Map<String, Object> props, List<String> requiredFields) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", props);
    if (!requiredFields.isEmpty()) {
      schema.put("required", requiredFields);
    }
    schema.put("additionalProperties", Boolean.FALSE);
    return Map.copyOf(schema);
  }
}
