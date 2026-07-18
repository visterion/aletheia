package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code link_contract} write tool handler; delegates to {@link
 * WriteTools#linkContract(long, String, String)}.
 */
@Component
@Order(17)
public class LinkContractToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public LinkContractToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "link_contract";
  }

  @Override
  public String description() {
    return "Link a contract to a HiveMem contract cell. contractId is a contracts.id -- get it"
        + " from list_unmatched_recurring/get_review_queue (for a mandate-less obligation,"
        + " confirm_counterparty/dismiss_counterparty without a contractId first to"
        + " materialize its contract row). Find the cell id via HiveMem:search with"
        + " where.realm=contracts (or the topic documenting the contract).";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("contractId", "contracts.id to link")
        .requiredString("hivememCellId", "the HiveMem cell id")
        .optionalString("notes", "optional free-text notes")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long contractId = ArgumentParser.requiredLong(arguments, "contractId");
    String hivememCellId = ArgumentParser.requiredText(arguments, "hivememCellId");
    String notes = ArgumentParser.optionalText(arguments, "notes");
    return writeTools.linkContract(contractId, hivememCellId, notes);
  }
}
