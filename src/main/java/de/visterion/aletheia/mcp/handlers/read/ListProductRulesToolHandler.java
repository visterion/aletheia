package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ProductRuleService;
import de.visterion.aletheia.mcp.ToolHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code list_product_rules} read tool handler; delegates to {@link
 * ProductRuleService#listProductRules()}.
 *
 * <p>READ-scoped, amending spec §7's WRITER: it hides nothing. After rollout §8 step 2 {@code
 * product_rules} is granted to both DB roles, so a READER can already {@code SELECT
 * position_pattern} through {@code sql_query}, and {@code counterparty_transactions} already
 * returns the raw {@code remittance_info} the pattern is derived from. WRITER-scoping would have
 * withheld only the residue surface (spec §6, {@code rootsMismatched}) from exactly the role that
 * does read-only analysis, and would have broken the {@code list_tag_rules} precedent, which is a
 * READ tool.
 *
 * <p>The {@code @Order} keeps it next to the four writing product-rule tools in {@code tools/list}
 * rather than among the other reads: an LLM meets them as one group.
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
        + " rootsVisited counts every booking of the creditor the resolver looked at, including"
        + " the ones it deliberately skipped because a human had split or re-attributed them; a"
        + " dry run's candidateRoots excludes those, so candidateRoots <= rootsVisited by design."
        + "\n\nKeywords: Regel, Regeln, Produkt, Tarif, Versicherung";
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return productRuleService.listProductRules();
  }
}
