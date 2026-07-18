package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.Allocation;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.TxReference;
import de.visterion.aletheia.mcp.WriteTools;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code split_transaction} write tool handler; delegates to {@link
 * WriteTools#splitTransaction(TxReference, List, Boolean)}.
 */
@Component
@Order(20)
public class SplitTransactionToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public SplitTransactionToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "split_transaction";
  }

  @Override
  public String description() {
    return "Split an existing raw transaction into logical child positions (replace semantics)."
        + " If allocations is null/empty or unsplit=true, deletes all children (unsplit)."
        + " Otherwise validates that sum(allocations.amount) equals the original transaction"
        + " amount exactly and that each allocation.amount is strictly positive, deletes any"
        + " prior children for this parent, creates name-based counterparties on demand"
        + " (Bargeld auto gets nature=umbuchung), inserts deterministic synthetic child rows"
        + " with split_parent_* backrefs, import_id=null, occurrence_index=0, and attribution"
        + " driven from counterpartyId identity (creditor_id/iban/name) or displayName for"
        + " correct resolution on purchase vs pseudo parts. Idempotent replace. All inside"
        + " one transaction.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredObject(
            "tx",
            "reference to the parent transaction to split (by natural key)",
            ToolInputSchema.object().requiredString("contentHash", "").requiredInteger("occurrenceIndex", ""))
        .optionalObjectList(
            "allocations",
            "list of target allocations; null/empty triggers unsplit. Each allocation targets"
                + " either an existing counterpartyId or a displayName (to create name-based"
                + " CP). Each allocation.amount must be strictly positive.",
            ToolInputSchema.object()
                .requiredDecimal("amount", "")
                .requiredLong("counterpartyId", "")
                .requiredString("displayName", "")
                .requiredString("mandateId", "")
                .requiredString("remittanceInfo", ""))
        .optionalBoolean("unsplit", "if true force unsplit (delete children) even if allocations provided")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    TxReference tx = ArgumentParser.requiredTxReference(arguments, "tx");
    List<Allocation> allocations = ArgumentParser.optionalAllocationList(arguments, "allocations");
    Boolean unsplit = ArgumentParser.optionalBoolean(arguments, "unsplit");
    return writeTools.splitTransaction(tx, allocations, unsplit);
  }
}
