package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ProductRuleService;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code list_product_rules} read-only tool handler; delegates to {@link
 * ProductRuleService#listProductRules()}.
 *
 * <p>It reads only, but it is a WRITER-scoped tool (spec §7): a rule's {@code positionPattern} is a
 * creditor's remittance format, and it is exposed to the same audience that may author one.
 */
@Component
@Order(29)
public class ListProductRulesToolHandler implements ToolHandler {

  private final ProductRuleService productRuleService;

  public ListProductRulesToolHandler(ProductRuleService productRuleService) {
    this.productRuleService = productRuleService;
  }

  @Override
  public String name() {
    return "list_product_rules";
  }

  @Override
  public String description() {
    return "Answers: which creditors split their mandate into per-product contracts, and does each"
        + " rule still explain that creditor's bookings? Oldest first, with the counters of the"
        + " last resolver pass: rootsVisited, rootsSplit, rootsStamped and rootsMismatched. A"
        + " rising rootsMismatched means the creditor changed its remittance format and those"
        + " bookings are being left untouched -- fix the pattern with update_product_rule."
        + "\n\nKeywords: Regel, Regeln, Produkt, Tarif, Versicherung";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return productRuleService.listProductRules();
  }
}
