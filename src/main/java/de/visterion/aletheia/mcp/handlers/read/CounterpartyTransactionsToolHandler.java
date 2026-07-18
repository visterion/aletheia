package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code counterparty_transactions} read tool handler; delegates to {@link
 * ReadTools#counterpartyTransactions(long, Integer, LocalDate, LocalDate)}.
 */
@Component
@Order(11)
public class CounterpartyTransactionsToolHandler implements ToolHandler {

  private final ReadTools readTools;

  public CounterpartyTransactionsToolHandler(ReadTools readTools) {
    this.readTools = readTools;
  }

  @Override
  public String name() {
    return "counterparty_transactions";
  }

  @Override
  public String description() {
    return "The underlying bookings for one counterparty (evidence detail), optionally limited to"
        + " the last N days via period, or to an inclusive [dateFrom, dateTo] absolute"
        + " range on booking_date. When dateFrom and dateTo are both given, the absolute"
        + " range wins over period (period is ignored). Returns only current logical"
        + " positions: split parents are hidden (NOT EXISTS filter on split_parent_*);"
        + " children and unsplit originals are shown (javadoc references logical view)."
        + " Identity priority: attributed_name > creditor_id > iban > normalized name.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredLong("counterpartyId", "counterparties.id")
        .optionalInteger("period", "last N days; omit for all")
        .optionalDate("dateFrom", "inclusive range start (YYYY-MM-DD)")
        .optionalDate("dateTo", "inclusive range end (YYYY-MM-DD)")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    long counterpartyId = ArgumentParser.requiredLong(arguments, "counterpartyId");
    Integer period = ArgumentParser.optionalInteger(arguments, "period");
    LocalDate dateFrom = ArgumentParser.optionalDate(arguments, "dateFrom");
    LocalDate dateTo = ArgumentParser.optionalDate(arguments, "dateTo");
    return readTools.counterpartyTransactions(counterpartyId, period, dateFrom, dateTo);
  }
}
