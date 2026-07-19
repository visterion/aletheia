package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code merge_counterparty} write tool handler; delegates to {@link
 * WriteTools#mergeCounterparty(long, List, String)}.
 */
@Component
@Order(24)
public class MergeCounterpartyToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public MergeCounterpartyToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "merge_counterparty";
  }

  @Override
  public String description() {
    return "Fold several fragmented counterparties into one canonical entity. A service often"
        + " appears as multiple counterparties because its creditor-id, IBAN and name resolve"
        + " separately (e.g. one provider billing under several creditor-ids, or several own"
        + " bank sub-accounts). Re-points every source's bookings, tags, recurring series and"
        + " contracts onto the target; records each source identity as an alias so future"
        + " imports of the same variant create no new record; soft-deletes the sources. Target"
        + " wins on conflict (logged to history); a confirmed obligation on a source is"
        + " preserved (it upgrades the target's line). Not reversible.\n\nDo NOT use this to"
        + " merge two genuinely different merchants (it pools their series). In particular,"
        + " never merge two distinct payment-service-attributed merchants -- their evidence"
        + " shares the synthetic 'attributed' mandate and would re-lump.\n\nInputs: targetId"
        + " (the counterparty to keep), sourceIds (the fragments to fold in), reason.\n\nExample"
        + " -- fold sub-accounts that all resolve to the same bank into the one you keep:\n "
        + " merge_counterparty(targetId: 103, sourceIds: [361, 366, 422], reason: \"same bank,"
        + " sub-accounts\")\nExample -- a subscription split across creditor-id, IBAN and"
        + " name:\n  merge_counterparty(targetId: 512, sourceIds: [788, 913], reason: \"one"
        + " provider, identity variants\")\n\nAfter a merge, list_counterparties / evidence /"
        + " obligations_register / counterparty_transactions show one line with all bookings;"
        + " re-importing a folded variant adds no new record.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("targetId", "the counterparty to keep")
        .requiredLongList("sourceIds", "the fragments to fold into targetId")
        .requiredString("reason", "why these are the same entity (recorded on the alias rows)")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long targetId = ArgumentParser.requiredLong(arguments, "targetId");
    List<Long> sourceIds = ArgumentParser.requiredLongList(arguments, "sourceIds");
    String reason = ArgumentParser.requiredText(arguments, "reason");
    return writeTools.mergeCounterparty(targetId, sourceIds, reason);
  }
}
