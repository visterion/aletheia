package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.AggregateGroupBy;
import de.visterion.aletheia.mcp.AggregateMetric;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.CounterpartySelector;
import de.visterion.aletheia.mcp.CounterpartySelectorSchema;
import de.visterion.aletheia.mcp.Direction;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code aggregate} read tool handler; delegates to {@link
 * ReadTools#aggregate(LocalDate, LocalDate, AggregateGroupBy, AggregateMetric, Direction, Boolean,
 * List, CounterpartySelector)}.
 */
@Component
@Order(12)
public class AggregateToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public AggregateToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "aggregate";
  }

  @Override
  public String description() {
    return "Chart-ready aggregation over transactions for an inclusive [dateFrom, dateTo] date"
        + " range -- replaces in-head arithmetic. Value expression: for a single direction"
        + " (DBIT or CRDT), SUM/AVG/MEDIAN run on the always-positive amount filtered to"
        + " that direction; for direction=BOTH there is no direction filter and the amount"
        + " is signed (DBIT negated, CRDT positive) before aggregation, so SUM(BOTH) ="
        + " credit total minus debit total (can be negative). COUNT is always count(*),"
        + " unaffected by signing. Join-scope rule (M-1): when both counterpartyIds and"
        + " where are omitted AND byCounterparty=false, the aggregate runs directly over"
        + " transactions with no counterparty-identity join, so unresolved bookings (cash"
        + " withdrawals/fees with no creditor_id, iban, or name) are still counted."
        + " Scoping via counterpartyIds/where, or grouping byCounterparty=true, joins"
        + " through the identity-CASE resolution (attributed_name > creditor_id > iban >"
        + " normalized name)"
        + " and therefore excludes unresolved bookings. When byCounterparty=true, buckets"
        + " are keyed on counterparties.id (never displayName -- two distinct identities,"
        + " e.g. a creditor_id and an iban, can share one display name)."
        + " Logical view: split parents are excluded (NOT EXISTS on split_parent_*); only"
        + " current leaf positions (children and unsplit originals) are aggregated.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredDate("dateFrom", "inclusive range start")
        .requiredDate("dateTo", "inclusive range end")
        .requiredEnumString("groupBy", "TOTAL | MONTH | QUARTER | YEAR", "TOTAL", "MONTH", "QUARTER", "YEAR")
        .requiredEnumString("metric", "SUM | AVG | MEDIAN | COUNT", "SUM", "AVG", "MEDIAN", "COUNT")
        .requiredEnumString(
            "direction",
            "DBIT | CRDT | BOTH (BOTH nets signed amounts, no direction filter)",
            "DBIT",
            "CRDT",
            "BOTH")
        .optionalBoolean("byCounterparty", "also split buckets per counterparty, keyed on id")
        .optionalLongList("counterpartyIds", "explicit counterparty id scope; takes precedence over where")
        .optionalObject(
            "where",
            "declarative counterparty selector, resolved when counterpartyIds is absent",
            CounterpartySelectorSchema.where())
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    LocalDate dateFrom = ArgumentParser.requiredDate(arguments, "dateFrom");
    LocalDate dateTo = ArgumentParser.requiredDate(arguments, "dateTo");
    AggregateGroupBy groupBy = ArgumentParser.requiredEnum(arguments, "groupBy", AggregateGroupBy.class);
    AggregateMetric metric = ArgumentParser.requiredEnum(arguments, "metric", AggregateMetric.class);
    Direction direction = ArgumentParser.requiredEnum(arguments, "direction", Direction.class);
    Boolean byCounterparty = ArgumentParser.optionalBoolean(arguments, "byCounterparty");
    List<Long> counterpartyIds = ArgumentParser.optionalLongList(arguments, "counterpartyIds");
    CounterpartySelector where = ArgumentParser.counterpartySelector(arguments, "where");
    return readTools.aggregate(
        dateFrom, dateTo, groupBy, metric, direction, byCounterparty, counterpartyIds, where);
  }
}
