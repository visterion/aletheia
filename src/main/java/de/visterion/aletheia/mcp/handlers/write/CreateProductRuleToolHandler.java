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
 * Hand-rolled {@code create_product_rule} write tool handler; delegates to {@link
 * ProductRuleService#createProductRule(String, String, String, Boolean)}.
 */
@Component
@Order(27)
public class CreateProductRuleToolHandler implements ToolHandler {

  private final ProductRuleService productRuleService;

  public CreateProductRuleToolHandler(ProductRuleService productRuleService) {
    this.productRuleService = productRuleService;
  }

  @Override
  public String name() {
    return "create_product_rule";
  }

  @Override
  public String description() {
    return "Answers: one SEPA mandate pays for several products at once -- how do I get one"
        + " contract per product instead of a lump? Create the creditor's product rule."
        + " positionPattern is a regex matching ONE position of the remittance, applied globally;"
        + " it must declare the named capture groups product and amount, optionally policy."
        + " Read a few of the creditor's remittances with counterparty_transactions first and"
        + " derive the pattern from those -- do not guess it. Positions must sum EXACTLY to the"
        + " booking amount, otherwise the booking is left untouched and counted as a mismatch."
        + " dryRun=true writes nothing and returns the blast radius (candidateRoots, bookings"
        + " matched, positions parsed, sum mismatches) -- always dry-run first and check the"
        + " numbers. candidateRoots counts only the bookings the rule may act on and therefore"
        + " excludes those a human has split or re-attributed, which the resolver skips; the"
        + " rootsVisited of list_product_rules counts those too, so the two differ by exactly the"
        + " number of human-decided bookings. dryRun=false"
        + " persists the rule (enabled) and settles the substrate immediately, so the product"
        + " contracts appear in the same call. Product identity is the normalized product name, so"
        + " a mid-history capitalisation change stays one product."
        + "\n\nKeywords: Versicherung, Vertrag, Produkt, Tarif, Mandat, Regel, aufteilen";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredString("creditorId", "SEPA creditor identifier; must match a known counterparty")
        .requiredString(
            "positionPattern",
            "regex for ONE position, with named groups product and amount, optionally policy")
        .optionalString("notes", "free-form operator note")
        .requiredBoolean("dryRun", "true = preview only, write nothing")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    String creditorId = ArgumentParser.requiredText(arguments, "creditorId");
    String positionPattern = ArgumentParser.requiredText(arguments, "positionPattern");
    String notes = ArgumentParser.optionalText(arguments, "notes");
    Boolean dryRun = ArgumentParser.requiredBoolean(arguments, "dryRun");
    return productRuleService.createProductRule(creditorId, positionPattern, notes, dryRun);
  }
}
