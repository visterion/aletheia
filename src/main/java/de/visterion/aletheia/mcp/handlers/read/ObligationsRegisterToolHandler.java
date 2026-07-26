package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ListParams;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code obligations_register} read tool handler; delegates to {@link
 * ReadTools#obligationsRegister(ListParams)}.
 */
@Component
@Order(3)
public class ObligationsRegisterToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public ObligationsRegisterToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "obligations_register";
  }

  @Override
  public String description() {
    return "Answers: what recurring obligations (insurance, subscriptions, contracts) do I have"
        + " documented, and what do they cost per year? The documented obligations register:"
        + " confirmed contracts (TP1 contract grain -- one"
        + " row per confirmed contracts row, so a counterparty with two confirmed"
        + " contracts, e.g. two insurance policies, produces two rows) with annual cost,"
        + " tags and contract-link status, ordered by annual cost descending (contractId"
        + " ascending as tie-breaker), plus the total. Each row's annual cost is scoped to"
        + " its OWN contract -- never the counterparty's combined debit. totalAnnualCost is"
        + " the sum over ALL matching contracts, computed after minAmount but before"
        + " limit/offset -- it does NOT match the sum of the returned page once either"
        + " paging parameter is set. All debit/annual cost figures are derived from the"
        + " logical transaction view (NOT EXISTS on split_parent_* excludes superseded"
        + " parents). Excludes counterparties tagged with confirmed nature:zahlungsdienst;"
        + " auto tags do not exclude."
        + "\n\nKeywords: Versicherung, Versicherungen, Vertrag, Verträge, Beitrag, Verpflichtung,"
        + " Verpflichtungen, Abo, Miete, Strom, Haushalt, Finanzen, household finances";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalInteger("limit", "max rows to return (default 25); must be > 0")
        .optionalInteger("offset", "rows to skip for paging (default 0); must be >= 0")
        .optionalNumber(
            "minAmount",
            "only contracts whose estimated annual cost is >= this value (default: no filter)")
        .optionalBoolean(
            "verbose",
            "false (default): {contractId, counterpartyId, displayName, cadence, annualCost};"
                + " true: adds mandateId, identityType, tags, hasContract, hivememCellId;"
                + " null-valued fields are omitted from the JSON entirely")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    return readTools.obligationsRegister(
        new ListParams(
            ArgumentParser.optionalInteger(arguments, "limit"),
            ArgumentParser.optionalInteger(arguments, "offset"),
            ArgumentParser.optionalDecimal(arguments, "minAmount"),
            ArgumentParser.optionalBoolean(arguments, "verbose")));
  }
}
