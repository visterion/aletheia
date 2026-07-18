package de.visterion.aletheia.tagrules;

import de.visterion.aletheia.mcp.Direction;
import de.visterion.aletheia.mcp.TagDimension;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a rule's conditions/actions before persistence or execution (spec §3, §7). Every
 * failure is an {@link IllegalArgumentException} (surfaced as an MCP tool error, like
 * {@code classify_counterparty}).
 */
public final class TagRuleValidator {

  private TagRuleValidator() {}

  public static void validate(List<RuleCondition> conditions, List<RuleAction> actions) {
    if (conditions == null || conditions.isEmpty()) {
      throw new IllegalArgumentException("a rule needs at least one condition");
    }
    if (actions == null || actions.isEmpty()) {
      throw new IllegalArgumentException("a rule needs at least one action");
    }
    for (RuleCondition c : conditions) {
      validateCondition(c);
    }
    Set<String> seen = new HashSet<>();
    for (RuleAction a : actions) {
      validateAction(a);
      if (!seen.add(a.dimension() + " " + a.value())) {
        throw new IllegalArgumentException(
            "duplicate action pair: " + a.dimension() + "=" + a.value());
      }
    }
  }

  private static void validateCondition(RuleCondition c) {
    if (c == null || c.field() == null || c.op() == null) {
      throw new IllegalArgumentException("each condition needs a field, an op, and a value");
    }
    if (c.value() == null || c.value().isBlank()) {
      throw new IllegalArgumentException("condition value must not be blank");
    }
    boolean textField =
        c.field() == RuleField.remittance_info || c.field() == RuleField.counterparty_name;
    if (c.op() == RuleOp.contains && !textField) {
      throw new IllegalArgumentException(
          "op 'contains' is only valid on remittance_info/counterparty_name; use 'equals' for "
              + c.field());
    }
    if (c.field() == RuleField.direction) {
      // Must match a real stored wire value so the rule can ever match (BOTH is an aggregate-only
      // sentinel, never stored on a transaction).
      if (!c.value().equals(Direction.DBIT.name()) && !c.value().equals(Direction.CRDT.name())) {
        throw new IllegalArgumentException("direction value must be 'DBIT' or 'CRDT'");
      }
    }
  }

  private static void validateAction(RuleAction a) {
    if (a == null || a.dimension() == null) {
      throw new IllegalArgumentException("each action needs a dimension and a value");
    }
    boolean known = false;
    for (TagDimension d : TagDimension.values()) {
      if (d.name().equals(a.dimension())) {
        known = true;
        break;
      }
    }
    if (!known) {
      throw new IllegalArgumentException(
          "unknown dimension '" + a.dimension() + "'; use domain|nature|necessity");
    }
    if (a.value() == null || a.value().isBlank()) {
      throw new IllegalArgumentException("action value must not be blank");
    }
  }
}
