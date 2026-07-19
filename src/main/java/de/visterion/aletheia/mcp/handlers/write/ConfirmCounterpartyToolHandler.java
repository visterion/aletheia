package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.Cadence;
import de.visterion.aletheia.mcp.CounterpartySelector;
import de.visterion.aletheia.mcp.CounterpartySelectorSchema;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code confirm_counterparty} write tool handler; delegates to {@link
 * WriteTools#confirmCounterparty(Long, Long, List, CounterpartySelector, Boolean, Cadence,
 * BigDecimal, BigDecimal, BigDecimal)}.
 */
@Component
@Order(16)
public class ConfirmCounterpartyToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public ConfirmCounterpartyToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "confirm_counterparty";
  }

  @Override
  public String description() {
    return "The human's 'yes'. SINGLE: counterpartyId (+ optional contractId to confirm just that"
        + " contract). BATCH: counterpartyIds OR a where-selector (never both, never with"
        + " contractId/counterpartyId) -- each id is confirmed at counterparty level"
        + " (contractId=null): a mandate-less recurring series is materialized+confirmed"
        + " (appears in the obligations register); otherwise auto tags/status flip to"
        + " confirmed. An OPEN mandate contract is NOT confirmed by batch -- use single"
        + " confirm(id, contractId) for those. Batches of 200+ require confirm=true."
        + " The where selector also supports: txnCountMax (logical booking count <= N; a"
        + " counterparty with no bookings counts as 0), natureNotIn/domainNotIn (exclude"
        + " counterparties carrying any of these nature/domain tags), amountMin/amountMax"
        + " (largest single booking in absolute EUR, credits included, within these bounds;"
        + " counterparties with no bookings are excluded), and lastSeenBefore/lastSeenAfter"
        + " (last booking date, inclusive). SINGLE-item mode also accepts an optional cadence"
        + " (with typicalAmount and optional amountMin/amountMax): when supplied, this fuses"
        + " series-creation + contract-materialize + confirm into one call, e.g. confirming a"
        + " newly-seen quarterly insurance debit as recurring at EUR 45.00 in a single step.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .optionalLong("counterpartyId", "counterparties.id (single-item mode)")
        .optionalLong("contractId", "contracts.id to confirm (single-item only)")
        .optionalLongList("counterpartyIds", "explicit ids (batch mode)")
        .optionalObject("where", "where-selector (batch mode)", CounterpartySelectorSchema.where())
        .optionalBoolean("confirm", "must be true for a batch of 200 or more")
        .optionalEnumString(
            "cadence",
            "recurrence interval; when set, materializes the series+contract for a single"
                + " counterpartyId",
            "monthly", "quarterly", "half_yearly", "yearly", "irregular")
        .optionalDecimal("typicalAmount", "representative amount per occurrence (with cadence)")
        .optionalDecimal("amountMin", "smallest observed amount (with cadence), optional")
        .optionalDecimal("amountMax", "largest observed amount (with cadence), optional")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    Long counterpartyId = ArgumentParser.optionalLong(arguments, "counterpartyId");
    Long contractId = ArgumentParser.optionalLong(arguments, "contractId");
    List<Long> counterpartyIds = ArgumentParser.optionalLongList(arguments, "counterpartyIds");
    CounterpartySelector where = ArgumentParser.counterpartySelector(arguments, "where");
    Boolean confirm = ArgumentParser.optionalBoolean(arguments, "confirm");
    Cadence cadence = ArgumentParser.optionalEnum(arguments, "cadence", Cadence.class);
    BigDecimal typicalAmount = ArgumentParser.optionalDecimal(arguments, "typicalAmount");
    BigDecimal amountMin = ArgumentParser.optionalDecimal(arguments, "amountMin");
    BigDecimal amountMax = ArgumentParser.optionalDecimal(arguments, "amountMax");
    return writeTools.confirmCounterparty(
        counterpartyId, contractId, counterpartyIds, where, confirm,
        cadence, typicalAmount, amountMin, amountMax);
  }
}
