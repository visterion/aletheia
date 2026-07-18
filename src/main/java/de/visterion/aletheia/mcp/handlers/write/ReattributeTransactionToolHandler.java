package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
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
 * Hand-rolled {@code reattribute_transaction} write tool handler; delegates to {@link
 * WriteTools#reattributeTransaction(List, String)}.
 */
@Component
@Order(19)
public class ReattributeTransactionToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public ReattributeTransactionToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "reattribute_transaction";
  }

  @Override
  public String description() {
    return "Stamp the real merchant onto passthrough bookings (Adyen/LogPay/Klarna, where the"
        + " deterministic PayPal resolver cannot parse it). Pass the exact transactions as"
        + " refs (get contentHash/occurrenceIndex from counterparty_transactions) and the"
        + " real merchant as attributedName; pass attributedName=null to clear the"
        + " attribution. Sets attribution_source='manual', which wins permanently over the"
        + " PayPal resolver. Attribute a whole recurring series consistently (all its refs)."
        + " Clearing a PayPal-creditor row is transient (the deterministic resolver re-stamps"
        + " it) -- to correct a wrong PayPal parse, set a manual name instead. Teardown of a"
        + " no-longer-wanted merchant is dismiss_counterparty(merchantId, contractId).";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredObjectList(
            "refs",
            "the exact transactions to (re)attribute",
            ToolInputSchema.object().requiredString("contentHash", "").requiredInteger("occurrenceIndex", ""))
        .optionalString("attributedName", "the real merchant name; null clears the attribution")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    List<TxReference> refs = ArgumentParser.requiredTxReferenceList(arguments, "refs");
    String attributedName = ArgumentParser.optionalText(arguments, "attributedName");
    return writeTools.reattributeTransaction(refs, attributedName);
  }
}
