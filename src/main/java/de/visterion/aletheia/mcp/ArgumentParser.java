package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
}
