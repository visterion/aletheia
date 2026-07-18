package de.visterion.aletheia.mcp;

import de.visterion.aletheia.tagrules.RuleAction;
import de.visterion.aletheia.tagrules.RuleCondition;
import de.visterion.aletheia.tagrules.RuleField;
import de.visterion.aletheia.tagrules.RuleOp;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Static helpers for reading typed values out of {@code tools/call} JSON-RPC arguments.
 *
 * <p>All missing-required and wrong-type failures throw {@link McpArgumentException}, a distinct
 * type the dispatcher maps to JSON-RPC error code {@code -32602}. Optional helpers return {@code
 * null} when the field is absent or JSON {@code null}.
 */
public final class ArgumentParser {

  private ArgumentParser() {}

  public static JsonNode requiredNode(JsonNode arguments, String name) {
    if (arguments == null || !arguments.has(name) || arguments.get(name).isNull()) {
      throw new McpArgumentException("Missing " + name);
    }
    return arguments.get(name);
  }

  public static JsonNode optionalNode(JsonNode arguments, String name) {
    if (arguments == null || !arguments.has(name) || arguments.get(name).isNull()) {
      return null;
    }
    return arguments.get(name);
  }

  public static String requiredText(JsonNode arguments, String name) {
    JsonNode node = requiredNode(arguments, name);
    if (!node.isTextual()) {
      throw new McpArgumentException("Invalid " + name);
    }
    String value = node.asString();
    if (value.isBlank()) {
      throw new McpArgumentException("Missing " + name);
    }
    return value;
  }

  public static String optionalText(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isTextual()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asString();
  }

  public static long requiredLong(JsonNode arguments, String name) {
    JsonNode node = requiredNode(arguments, name);
    if (!node.isIntegralNumber()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asLong();
  }

  public static Long optionalLong(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isIntegralNumber()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asLong();
  }

  public static int requiredInteger(JsonNode arguments, String name) {
    JsonNode node = requiredNode(arguments, name);
    if (!node.isIntegralNumber()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asInt();
  }

  public static Integer optionalInteger(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isIntegralNumber()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asInt();
  }

  public static BigDecimal requiredDecimal(JsonNode arguments, String name) {
    JsonNode node = requiredNode(arguments, name);
    if (!node.isNumber()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.decimalValue();
  }

  public static BigDecimal optionalDecimal(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isNumber()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.decimalValue();
  }

  public static Boolean optionalBoolean(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isBoolean()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asBoolean();
  }

  /**
   * Parses a required boolean field. Used where the captured tool schema marks a boolean
   * required (e.g. {@code dryRun}, {@code enabled}) but {@link ToolInputSchema} has no {@code
   * requiredBoolean} builder -- those schemas describe the field via {@code optionalBoolean} and
   * this method enforces presence at parse time instead.
   */
  public static boolean requiredBoolean(JsonNode arguments, String name) {
    JsonNode node = requiredNode(arguments, name);
    if (!node.isBoolean()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return node.asBoolean();
  }

  public static LocalDate requiredDate(JsonNode arguments, String name) {
    String text = requiredText(arguments, name);
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw new McpArgumentException("Invalid " + name);
    }
  }

  public static LocalDate optionalDate(JsonNode arguments, String name) {
    String text = optionalText(arguments, name);
    if (text == null) {
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw new McpArgumentException("Invalid " + name);
    }
  }

  public static List<String> optionalTextList(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isArray()) {
      throw new McpArgumentException("Invalid " + name);
    }
    List<String> values = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (!item.isTextual()) {
        throw new McpArgumentException("Invalid " + name);
      }
      values.add(item.asString());
    }
    return List.copyOf(values);
  }

  public static List<Long> optionalLongList(JsonNode arguments, String name) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    if (!node.isArray()) {
      throw new McpArgumentException("Invalid " + name);
    }
    List<Long> values = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (!item.isIntegralNumber()) {
        throw new McpArgumentException("Invalid " + name);
      }
      values.add(item.asLong());
    }
    return List.copyOf(values);
  }

  /**
   * Parses a {@code where} argument object into a {@link CounterpartySelector}. Field names match
   * {@link CounterpartySelector}'s 8 components exactly. Returns {@code null} when {@code where}
   * is absent or JSON {@code null} (no selector given).
   */
  public static CounterpartySelector counterpartySelector(JsonNode arguments, String name) {
    JsonNode where = optionalNode(arguments, name);
    if (where == null) {
      return null;
    }
    Boolean untagged = optionalBoolean(where, "untagged");
    String namePattern = optionalText(where, "namePattern");
    BigDecimal minAnnualCost = optionalDecimal(where, "minAnnualCost");
    String predominantDirectionText = optionalText(where, "predominantDirection");
    Direction predominantDirection =
        predominantDirectionText == null ? null : parseEnum(Direction.class, predominantDirectionText, "predominantDirection");
    List<String> domainIn = optionalTextList(where, "domainIn");
    List<String> natureIn = optionalTextList(where, "natureIn");
    Boolean reviewed = optionalBoolean(where, "reviewed");
    Boolean hasContract = optionalBoolean(where, "hasContract");
    return new CounterpartySelector(
        untagged, namePattern, minAnnualCost, predominantDirection, domainIn, natureIn, reviewed, hasContract);
  }

  /** Parses a required enum-string field, throwing {@link McpArgumentException} on a bad value. */
  public static <E extends Enum<E>> E requiredEnum(JsonNode arguments, String name, Class<E> enumType) {
    String text = requiredText(arguments, name);
    return parseEnum(enumType, text, name);
  }

  /** Parses an optional enum-string field, returning {@code null} when absent. */
  public static <E extends Enum<E>> E optionalEnum(JsonNode arguments, String name, Class<E> enumType) {
    String text = optionalText(arguments, name);
    if (text == null) {
      return null;
    }
    return parseEnum(enumType, text, name);
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String text, String name) {
    try {
      return Enum.valueOf(enumType, text);
    } catch (IllegalArgumentException e) {
      throw new McpArgumentException("Invalid " + name);
    }
  }

  // --- generic array-of-objects / single-object parsing ---

  /**
   * Parses a required JSON object field via {@code itemMapper}. Throws {@link
   * McpArgumentException} when {@code name} is missing/null or not a JSON object.
   */
  public static <T> T requiredObject(JsonNode arguments, String name, Function<JsonNode, T> itemMapper) {
    JsonNode node = requiredNode(arguments, name);
    if (!node.isObject()) {
      throw new McpArgumentException("Invalid " + name);
    }
    return itemMapper.apply(node);
  }

  /**
   * Parses a required JSON array of objects via {@code itemMapper}, applied per element. Throws
   * {@link McpArgumentException} when {@code name} is missing/null, not an array, or any element
   * is not a JSON object.
   */
  public static <T> List<T> requiredObjectList(
      JsonNode arguments, String name, Function<JsonNode, T> itemMapper) {
    JsonNode node = requiredNode(arguments, name);
    return mapObjectArray(node, name, itemMapper);
  }

  /**
   * Parses an optional JSON array of objects via {@code itemMapper}. Returns {@code null} when
   * {@code name} is absent or JSON {@code null}.
   */
  public static <T> List<T> optionalObjectList(
      JsonNode arguments, String name, Function<JsonNode, T> itemMapper) {
    JsonNode node = optionalNode(arguments, name);
    if (node == null) {
      return null;
    }
    return mapObjectArray(node, name, itemMapper);
  }

  private static <T> List<T> mapObjectArray(JsonNode node, String name, Function<JsonNode, T> itemMapper) {
    if (!node.isArray()) {
      throw new McpArgumentException("Invalid " + name);
    }
    List<T> values = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (!item.isObject()) {
        throw new McpArgumentException("Invalid " + name);
      }
      values.add(itemMapper.apply(item));
    }
    return List.copyOf(values);
  }

  // --- typed array-of-objects / single-object parsing for the write tools ---

  private static TxReference txReference(JsonNode item) {
    return new TxReference(requiredText(item, "contentHash"), requiredInteger(item, "occurrenceIndex"));
  }

  /** Parses the required {@code tx} object argument into a {@link TxReference}. */
  public static TxReference requiredTxReference(JsonNode arguments, String name) {
    return requiredObject(arguments, name, ArgumentParser::txReference);
  }

  /** Parses the required {@code refs} array argument into a list of {@link TxReference}s. */
  public static List<TxReference> requiredTxReferenceList(JsonNode arguments, String name) {
    return requiredObjectList(arguments, name, ArgumentParser::txReference);
  }

  /**
   * Parses one {@code allocations} item into an {@link Allocation}. {@code counterpartyId},
   * {@code displayName}, {@code mandateId}, and {@code remittanceInfo} are all optional (a
   * caller-supplied JSON {@code null} is a legitimate "not set" value -- {@link
   * TransactionSplitService} branches on {@code counterpartyId == null} to fall back to
   * name-based attribution); only {@code amount} is required.
   */
  private static Allocation allocation(JsonNode item) {
    return new Allocation(
        optionalLong(item, "counterpartyId"),
        optionalText(item, "displayName"),
        optionalText(item, "mandateId"),
        requiredDecimal(item, "amount"),
        optionalText(item, "remittanceInfo"));
  }

  /** Parses the optional {@code allocations} array argument into a list of {@link Allocation}s. */
  public static List<Allocation> optionalAllocationList(JsonNode arguments, String name) {
    return optionalObjectList(arguments, name, ArgumentParser::allocation);
  }

  private static TagInput tagInput(JsonNode item) {
    return new TagInput(requiredText(item, "dimension"), requiredText(item, "value"));
  }

  /** Parses the required {@code tags} array argument into a list of {@link TagInput}s. */
  public static List<TagInput> requiredTagInputList(JsonNode arguments, String name) {
    return requiredObjectList(arguments, name, ArgumentParser::tagInput);
  }

  private static RuleCondition ruleCondition(JsonNode item) {
    RuleField field = requiredEnum(item, "field", RuleField.class);
    RuleOp op = requiredEnum(item, "op", RuleOp.class);
    String value = requiredText(item, "value");
    return new RuleCondition(field, op, value);
  }

  /** Parses the required {@code conditions} array argument into a list of {@link RuleCondition}s. */
  public static List<RuleCondition> requiredRuleConditionList(JsonNode arguments, String name) {
    return requiredObjectList(arguments, name, ArgumentParser::ruleCondition);
  }

  private static RuleAction ruleAction(JsonNode item) {
    return new RuleAction(requiredText(item, "dimension"), requiredText(item, "value"));
  }

  /** Parses the required {@code actions} array argument into a list of {@link RuleAction}s. */
  public static List<RuleAction> requiredRuleActionList(JsonNode arguments, String name) {
    return requiredObjectList(arguments, name, ArgumentParser::ruleAction);
  }
}
