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
 * Hand-rolled {@code delete_product_rule} write tool handler; delegates to {@link
 * ProductRuleService#deleteProductRule(Long, Boolean)}.
 */
@Component
@Order(31)
public class DeleteProductRuleToolHandler implements ToolHandler {

  private final ProductRuleService productRuleService;

  public DeleteProductRuleToolHandler(ProductRuleService productRuleService) {
    this.productRuleService = productRuleService;
  }

  @Override
  public String name() {
    return "delete_product_rule";
  }

  @Override
  public String description() {
    return "Answers: how do I undo a product split entirely and go back to one contract per"
        + " mandate? Delete a product rule and revert it in one step: the per-product rows are"
        + " removed, the stamps cleared, the strictly-auto per-product contracts and their"
        + " recurring series deleted, and a mandate contract that was ended when the split was"
        + " rolled out is reopened -- otherwise the creditor would disappear from the register,"
        + " the review queue and the unmatched list at once. A per-product contract you confirmed,"
        + " dismissed or ended is NOT deleted (a human decision is never destroyed by a tool call);"
        + " it is named in the ack and then describes a product no booking carries any more --"
        + " clean it up deliberately. To fix a pattern, use update_product_rule instead: it keeps"
        + " the contracts. dryRun=true reports the blast radius and writes nothing."
        + "\n\nKeywords: Regel, Produkt, löschen, rückgängig";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("ruleId", "product_rules.id")
        .optionalBoolean("dryRun", "true = preview the revert only, write nothing")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long ruleId = ArgumentParser.requiredLong(arguments, "ruleId");
    Boolean dryRun = ArgumentParser.optionalBoolean(arguments, "dryRun");
    return productRuleService.deleteProductRule(ruleId, dryRun);
  }
}
