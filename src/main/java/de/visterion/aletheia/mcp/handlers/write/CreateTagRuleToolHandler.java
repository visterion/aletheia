package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import de.visterion.aletheia.tagrules.RuleAction;
import de.visterion.aletheia.tagrules.RuleCondition;
import de.visterion.aletheia.tagrules.RuleField;
import de.visterion.aletheia.tagrules.RuleOp;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code create_tag_rule} write tool handler; delegates to {@link
 * WriteTools#createTagRule(String, List, List, Boolean, Boolean, Boolean)}.
 *
 * <p>{@code dryRun} is required by {@link WriteTools#createTagRule}'s {@code @ToolParam} (no
 * {@code required = false}), but {@link ToolInputSchema} has no {@code requiredBoolean} builder
 * -- like {@code set_tag_rule_enabled}'s {@code enabled}, the schema declares it via {@code
 * optionalBoolean} and {@link #call} enforces presence with {@link
 * ArgumentParser#requiredBoolean}.
 */
@Component
@Order(21)
public class CreateTagRuleToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public CreateTagRuleToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "create_tag_rule";
  }

  @Override
  public String description() {
    return "Create a persistent auto-tagging rule (Outlook-style). conditions are AND-ed; actions set"
        + " tags (source=confirmed) on matching counterparties, overwriting 'auto' tags and"
        + " skipping dimensions already 'confirmed'. dryRun=true writes nothing and returns the"
        + " match preview -- always dry-run first. dryRun=false persists (enabled) and, if"
        + " backfill=true, tags existing counterparties now; the rule also runs on every future"
        + " ingest. A match set of 200+ needs confirm=true.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredString("name", "human-readable rule name")
        .requiredObjectList(
            "conditions",
            "AND-ed conditions {field, op, value}; >=1",
            ToolInputSchema.object()
                .requiredEnumString(
                    "field",
                    "",
                    RuleField.remittance_info.name(),
                    RuleField.counterparty_name.name(),
                    RuleField.creditor_id.name(),
                    RuleField.counterparty_iban.name(),
                    RuleField.direction.name())
                .requiredEnumString("op", "", RuleOp.contains.name(), RuleOp.equals.name())
                .requiredString("value", ""))
        .requiredObjectList(
            "actions",
            "tags to set {dimension, value}; >=1",
            ToolInputSchema.object().requiredString("dimension", "").requiredString("value", ""))
        .optionalBoolean("dryRun", "true = preview only, write nothing")
        .optionalBoolean("backfill", "when persisting, also tag existing counterparties now")
        .optionalBoolean("confirm", "must be true to apply a rule matching 200+ counterparties")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    String name = ArgumentParser.requiredText(arguments, "name");
    List<RuleCondition> conditions = ArgumentParser.requiredRuleConditionList(arguments, "conditions");
    List<RuleAction> actions = ArgumentParser.requiredRuleActionList(arguments, "actions");
    Boolean dryRun = ArgumentParser.requiredBoolean(arguments, "dryRun");
    Boolean backfill = ArgumentParser.optionalBoolean(arguments, "backfill");
    Boolean confirm = ArgumentParser.optionalBoolean(arguments, "confirm");
    return writeTools.createTagRule(name, conditions, actions, dryRun, backfill, confirm);
  }
}
