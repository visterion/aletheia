package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.Cadence;
import de.visterion.aletheia.mcp.TagSource;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code mark_recurring} write tool handler; delegates to {@link
 * WriteTools#markRecurring(long, Long, Cadence, BigDecimal, BigDecimal, BigDecimal, TagSource,
 * BigDecimal)}.
 */
@Component
@Order(15)
public class MarkRecurringToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public MarkRecurringToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "mark_recurring";
  }

  @Override
  public String description() {
    return "Record/replace the recurring series for a counterparty, keyed by (counterparty_id,"
        + " contract_id) -- pass contractId (a contracts.id) to target that specific"
        + " contract's series, or null for the counterparty's mandate-less series. An"
        + " auto-source call can never overwrite an already-confirmed row. Never sets"
        + " counterparties.reviewed or status. Note: on a MANDATE contract (a contractId"
        + " whose contracts row has a mandate_id), measured values such as typical_amount"
        + " are refreshed from transactions by ContractResolver on every startup, so a"
        + " manual override here is not durable for mandate contracts -- mandate-less"
        + " series (contractId=null) are not resolver-owned.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("counterpartyId", "counterparties.id")
        .optionalLong("contractId", "contracts.id to target, or null for the mandate-less series")
        .requiredEnumString(
            "cadence", "recurrence interval", "monthly", "quarterly", "half_yearly", "yearly", "irregular")
        .requiredDecimal("typicalAmount", "the representative amount per occurrence")
        .optionalDecimal("amountMin", "smallest observed amount, optional")
        .optionalDecimal("amountMax", "largest observed amount, optional")
        .requiredEnumString("source", "provenance of this classification", "auto", "confirmed")
        .optionalDecimal("confidence", "0..1, optional")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long counterpartyId = ArgumentParser.requiredLong(arguments, "counterpartyId");
    Long contractId = ArgumentParser.optionalLong(arguments, "contractId");
    Cadence cadence = ArgumentParser.requiredEnum(arguments, "cadence", Cadence.class);
    BigDecimal typicalAmount = ArgumentParser.requiredDecimal(arguments, "typicalAmount");
    BigDecimal amountMin = ArgumentParser.optionalDecimal(arguments, "amountMin");
    BigDecimal amountMax = ArgumentParser.optionalDecimal(arguments, "amountMax");
    TagSource source = ArgumentParser.requiredEnum(arguments, "source", TagSource.class);
    BigDecimal confidence = ArgumentParser.optionalDecimal(arguments, "confidence");
    return writeTools.markRecurring(
        counterpartyId, contractId, cadence, typicalAmount, amountMin, amountMax, source, confidence);
  }
}
