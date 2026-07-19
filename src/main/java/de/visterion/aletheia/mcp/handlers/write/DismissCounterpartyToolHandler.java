package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.CounterpartySelector;
import de.visterion.aletheia.mcp.CounterpartySelectorSchema;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code dismiss_counterparty} write tool handler; delegates to {@link
 * WriteTools#dismissCounterparty(Long, Long, List, CounterpartySelector, String, Boolean)}.
 */
@Component
@Order(18)
public class DismissCounterpartyToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public DismissCounterpartyToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "dismiss_counterparty";
  }

  @Override
  public String description() {
    return "Mark counterparties as not-an-obligation. SINGLE: pass counterpartyId (optionally"
        + " contractId to dismiss just that contract). BATCH: pass counterpartyIds OR a"
        + " where-selector (never both, never with contractId/counterpartyId) -- each id is"
        + " dismissed at counterparty level (its mandate-less recurring series is"
        + " materialized+dismissed; a non-recurring counterparty gets status='dismissed')."
        + " A no-contractId dismiss also sets reviewed=true. Batches of 200+ require"
        + " confirm=true; over 1000 rejected. reason is required."
        + " The where selector also supports: txnCountMax (logical booking count <= N; a"
        + " counterparty with no bookings counts as 0), natureNotIn/domainNotIn (exclude"
        + " counterparties carrying any of these nature/domain tags), amountMin/amountMax"
        + " (largest single booking in absolute EUR, credits included, within these bounds;"
        + " counterparties with no bookings are excluded), and lastSeenBefore/lastSeenAfter"
        + " (last booking date, inclusive).";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalLong("counterpartyId", "counterparties.id (single-item mode)")
        .optionalLong("contractId", "contracts.id to dismiss (single-item only)")
        .optionalLongList("counterpartyIds", "explicit ids (batch mode)")
        .optionalObject("where", "where-selector (batch mode)", CounterpartySelectorSchema.where())
        .requiredString("reason", "why these were dismissed")
        .optionalBoolean("confirm", "must be true for a batch of 200 or more")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    Long counterpartyId = ArgumentParser.optionalLong(arguments, "counterpartyId");
    Long contractId = ArgumentParser.optionalLong(arguments, "contractId");
    List<Long> counterpartyIds = ArgumentParser.optionalLongList(arguments, "counterpartyIds");
    CounterpartySelector where = ArgumentParser.counterpartySelector(arguments, "where");
    String reason = ArgumentParser.requiredText(arguments, "reason");
    Boolean confirm = ArgumentParser.optionalBoolean(arguments, "confirm");
    return writeTools.dismissCounterparty(
        counterpartyId, contractId, counterpartyIds, where, reason, confirm);
  }
}
