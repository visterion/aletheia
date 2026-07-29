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
 * Hand-rolled {@code update_product_rule} write tool handler; delegates to {@link
 * ProductRuleService#updateProductRule(Long, String, String, Boolean)}.
 */
@Component
@Order(28)
public class UpdateProductRuleToolHandler implements ToolHandler {

  private final ProductRuleService productRuleService;

  public UpdateProductRuleToolHandler(ProductRuleService productRuleService) {
    this.productRuleService = productRuleService;
  }

  @Override
  public String name() {
    return "update_product_rule";
  }

  @Override
  public String description() {
    return "Answers: the creditor changed its remittance format (or my pattern missed a position)"
        + " -- how do I fix the rule without losing the contracts it already produced? Edit a"
        + " product rule in place. This is a graceful refresh: the split rows are recomputed and"
        + " the derived contracts survive. Deleting and re-creating the rule instead runs the full"
        + " revert and drops the auto contracts, so use this. An omitted field stays unchanged."
        + " dryRun=true writes nothing and returns the blast radius the new pattern would produce;"
        + " dryRun=false applies it and settles the substrate immediately."
        + "\n\nKeywords: Regel, Produkt, Tarif, ändern, korrigieren";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("ruleId", "product_rules.id")
        .optionalString(
            "positionPattern",
            "new regex for ONE position, with named groups product and amount, optionally policy")
        .optionalString("notes", "new operator note")
        .requiredBoolean("dryRun", "true = preview only, write nothing")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long ruleId = ArgumentParser.requiredLong(arguments, "ruleId");
    String positionPattern = ArgumentParser.optionalText(arguments, "positionPattern");
    String notes = ArgumentParser.optionalText(arguments, "notes");
    Boolean dryRun = ArgumentParser.requiredBoolean(arguments, "dryRun");
    return productRuleService.updateProductRule(ruleId, positionPattern, notes, dryRun);
  }
}
