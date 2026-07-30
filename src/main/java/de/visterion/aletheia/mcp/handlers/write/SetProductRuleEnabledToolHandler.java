package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ProductRuleService;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code set_product_rule_enabled} write tool handler; delegates to {@link
 * ProductRuleService#setProductRuleEnabled(Long, Boolean)}.
 */
@Component
@Order(30)
public class SetProductRuleEnabledToolHandler implements ToolHandler {

  private final ProductRuleService productRuleService;

  public SetProductRuleEnabledToolHandler(ProductRuleService productRuleService) {
    this.productRuleService = productRuleService;
  }

  @Override
  public String name() {
    return "set_product_rule_enabled";
  }

  @Override
  public String description() {
    return "Answers: how do I pause or resume a product rule without losing what it produced?"
        + " Pausing is a pause, not a revert: the existing per-product rows and contracts stay"
        + " exactly as they are, only no new work is done. Enabling settles the substrate"
        + " immediately, so the per-product contracts appear in the same call. Use"
        + " delete_product_rule when you really want the split undone."
        + "\n\nKeywords: Regel, Produkt, pausieren, aktivieren";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("ruleId", "product_rules.id")
        .requiredBoolean("enabled", "true = enabled, false = paused")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long ruleId = ArgumentParser.requiredLong(arguments, "ruleId");
    boolean enabled = ArgumentParser.requiredBoolean(arguments, "enabled");
    return productRuleService.setProductRuleEnabled(ruleId, enabled);
  }
}
