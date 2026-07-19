package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.CounterpartySelector;
import de.visterion.aletheia.mcp.CounterpartySelectorSchema;
import de.visterion.aletheia.mcp.TagInput;
import de.visterion.aletheia.mcp.TagSource;
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
 * Hand-rolled {@code classify_counterparty} write tool handler; delegates to {@link
 * WriteTools#classifyCounterparty(List, CounterpartySelector, List, TagSource, BigDecimal,
 * Boolean)}.
 */
@Component
@Order(14)
public class ClassifyCounterpartyToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public ClassifyCounterpartyToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "classify_counterparty";
  }

  @Override
  public String description() {
    return "Set/replace the tags for one or more dimensions on a batch of counterparties (explicit"
        + " ids or a where-selector). Never sets counterparties.reviewed or status -- only"
        + " confirm/dismiss do that. Batches of 200+ require confirm=true; batches over"
        + " 1000 are always rejected."
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
        .optionalLongList("counterpartyIds", "explicit counterparties.id list, optional")
        .optionalObject("where", "selector to resolve target ids, optional", CounterpartySelectorSchema.where())
        .requiredObjectList(
            "tags",
            "the {dimension, value} pairs to set",
            ToolInputSchema.object().requiredString("dimension", "").requiredString("value", ""))
        .requiredEnumString("source", "provenance of this classification", "auto", "confirmed")
        .optionalDecimal("confidence", "0..1, optional")
        .optionalBoolean("confirm", "must be true to run a batch of 200 or more")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    List<Long> counterpartyIds = ArgumentParser.optionalLongList(arguments, "counterpartyIds");
    CounterpartySelector where = ArgumentParser.counterpartySelector(arguments, "where");
    List<TagInput> tags = ArgumentParser.requiredTagInputList(arguments, "tags");
    TagSource source = ArgumentParser.requiredEnum(arguments, "source", TagSource.class);
    BigDecimal confidence = ArgumentParser.optionalDecimal(arguments, "confidence");
    Boolean confirm = ArgumentParser.optionalBoolean(arguments, "confirm");
    return writeTools.classifyCounterparty(counterpartyIds, where, tags, source, confidence, confirm);
  }
}
