package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code get_review_queue} read tool handler; delegates to {@link
 * ReadTools#getReviewQueue(Integer, Boolean)}.
 */
@Component
@Order(8)
public class GetReviewQueueToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public GetReviewQueueToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "get_review_queue";
  }

  @Override
  public String description() {
    return "The obligations still needing a human decision, ordered descending by estimated"
        + " annual cost. Two shapes: an OPEN contract (contracts.status='open') -- the"
        + " primary decision unit for any counterparty that has a contract layer at all,"
        + " confirm/dismiss it via confirm_counterparty/dismiss_counterparty(contractId);"
        + " or, for a counterparty with no contract row whatsoever (e.g. an ELV obligation"
        + " that never carried a mandate_id), the whole open counterparty (contractId"
        + " null) -- confirm/dismiss it without a contractId. Annual cost is"
        + " recurring.typical_amount * periods/year, or the DBIT-only spend of the last"
        + " 365 days if no recurring series is recorded yet, scoped to the contract when"
        + " one exists (never the counterparty's combined debit). Excludes"
        + " CRDT-predominant counterparties (salary, incoming transfers -- see"
        + " list_income); a counterparty with no evidence row yet (unknown direction)"
        + " stays in the queue, since nothing should skip human review. Compact by default"
        + " ({id, contractId, displayName, identityType, cadence, annualCostEstimate,"
        + " txnCount, lastSeen}); pass verbose=true for the full evidence/recurring blob."
        + " All evidence/spend numbers use the logical transaction view (parents with"
        + " split children are excluded via NOT EXISTS on split_parent_*).";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalInteger("limit", "max rows (default 50)")
        .optionalBoolean("verbose", "false default; true full evidence")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    Integer limit = ArgumentParser.optionalInteger(arguments, "limit");
    Boolean verbose = ArgumentParser.optionalBoolean(arguments, "verbose");
    return readTools.getReviewQueue(limit, verbose);
  }
}
