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

/** Hand-rolled {@code end_contract} write tool handler; delegates to {@link WriteTools#endContract}. */
@Component
@Order(26)
public class EndContractToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public EndContractToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "end_contract";
  }

  @Override
  public String description() {
    return "Mark an active (confirmed) contract as ended: it leaves the obligations register and the"
        + " unmatched-recurring list, but its history is preserved. Pass endDate (YYYY-MM-DD) or omit"
        + " for today, and an optional reason. Only a confirmed contract can be ended. To reactivate a"
        + " mandate-less contract later, confirm the counterparty again -- it reopens the ended row."
        + " Example: end_contract(contractId=17, endDate=\"2026-06-30\", reason=\"cancelled\").";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("contractId", "contracts.id to end")
        .optionalDate("endDate", "YYYY-MM-DD; defaults to today")
        .optionalString("reason", "why it ended, optional")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long contractId = ArgumentParser.requiredLong(arguments, "contractId");
    java.time.LocalDate endDate = ArgumentParser.optionalDate(arguments, "endDate");
    String reason = ArgumentParser.optionalText(arguments, "reason");
    return writeTools.endContract(contractId, endDate, reason);
  }
}
