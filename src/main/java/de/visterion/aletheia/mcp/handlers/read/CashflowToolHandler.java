package de.visterion.aletheia.mcp.handlers.read;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.Cashflow;
import de.visterion.aletheia.mcp.Cashflow.CashflowParams;
import de.visterion.aletheia.mcp.CashflowService;
import de.visterion.aletheia.mcp.McpArgumentException;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code cashflow} read tool handler; delegates to {@link
 * CashflowService#cashflow(CashflowParams)}.
 */
@Component
@Order(13)
public class CashflowToolHandler implements ToolHandler {

  private static final List<String> VALID_LEVELS = List.of("income_source", "domain", "counterparty");

  private final CashflowService cashflowService;

  public CashflowToolHandler(CashflowService cashflowService) {
    this.cashflowService = cashflowService;
  }

  @Override
  public String name() {
    return "cashflow";
  }

  @Override
  public String description() {
    return "Build a **Sankey-ready** money-flow for a period. USE THIS whenever the user wants a"
        + " monthly overview, budget flow, \"wohin fließt mein Geld\", or a finanzfluss-style"
        + " diagram — instead of hand-writing SQL. Income sources → budget → categories (→ top"
        + " counterparties); saving and internal-transfer handling are applied **server-side**"
        + " (do NOT re-classify or re-net in the client).\n"
        + "\n"
        + "Returns `{nodes:[{id,label,value,kind}], links:[{source,target,value}],"
        + " meta:{income,outflow,saving,saldo,excluded}}`."
        + " `kind ∈ income|budget|saving|expense|transfer|balance`. Result is **balanced** (Σ"
        + " inputs = Σ outputs at every interior node; income sources, leaf counterparties, and"
        + " the balance/saving/transfer nodes are terminal by design): if outflow > real income,"
        + " an `\"aus Rücklagen\"` **balance** node is added as input for the difference — this"
        + " is a real finding, never hide it.\n"
        + "\n"
        + "**Default recipe (one month):** `cashflow(dateFrom=\"<first day of month>\","
        + " dateTo=\"<last day of month>\")`, e.g. `cashflow(\"2026-06-01\",\"2026-06-30\")` —"
        + " use the month's real last day (dates are strict ISO; `\"2026-06-31\"` is rejected)."
        + " Defaults: internal transfers netted, passthroughs resolved/removed, depot buys"
        + " counted as saving (`investmentMode=\"as_saving\"`), depot dividends excluded from"
        + " income, `topN=6` (rest per level → \"Sonstiges\"). Params: `levels` (default"
        + " `[\"income_source\",\"domain\",\"counterparty\"]`), `excludeInternalTransfers=true`,"
        + " `excludePassthroughs=true`, `investmentMode`, `topN`, `minShare`. EUR, logical view"
        + " only (split parents excluded), **read-only** (never mutates).\n"
        + "\n"
        + "**Consume the result:** render directly as a Plotly sankey — `node.label ←"
        + " nodes[].label`, `link.{source,target,value} ← links[]` (ids are stable; map"
        + " id→index). `meta` gives the KPI headline (income / outflow / saldo). Example:"
        + " `cashflow(\"2026-06-01\",\"2026-06-30\")`.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredDate("dateFrom", "inclusive range start")
        .requiredDate("dateTo", "inclusive range end")
        .optionalEnumStringList(
            "levels",
            "Sankey depth on the expense side; default [income_source,domain,counterparty]",
            "income_source", "domain", "counterparty")
        .optionalBoolean("excludeInternalTransfers", "net internal transfers out (default true)")
        .optionalBoolean("excludePassthroughs", "resolve/remove passthroughs (default true)")
        .optionalEnumString(
            "investmentMode",
            "as_saving (default): depot buys counted as saving; exclude: depot buys excluded",
            "as_saving", "exclude")
        .optionalInteger("topN", "top N nodes per level, rest grouped into Sonstiges (default 6)")
        .optionalNumber("minShare", "minimum share threshold per node (default 0.0)")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    LocalDate dateFrom = ArgumentParser.requiredDate(arguments, "dateFrom");
    LocalDate dateTo = ArgumentParser.requiredDate(arguments, "dateTo");
    List<String> levels = ArgumentParser.optionalTextList(arguments, "levels");
    if (levels == null) {
      levels = VALID_LEVELS;
    } else {
      for (String lvl : levels) {
        if (!VALID_LEVELS.contains(lvl)) {
          throw new McpArgumentException(
              "levels values must be income_source|domain|counterparty");
        }
      }
    }
    Boolean exTransfers = ArgumentParser.optionalBoolean(arguments, "excludeInternalTransfers");
    Boolean exPassthroughs = ArgumentParser.optionalBoolean(arguments, "excludePassthroughs");
    String investment = ArgumentParser.optionalText(arguments, "investmentMode");
    if (investment != null && !investment.equals("as_saving") && !investment.equals("exclude")) {
      throw new McpArgumentException("investmentMode must be 'as_saving' or 'exclude'");
    }
    Integer topN = ArgumentParser.optionalInteger(arguments, "topN");
    BigDecimal minShare = ArgumentParser.optionalDecimal(arguments, "minShare");
    if (topN != null && topN < 0) {
      throw new McpArgumentException("topN must be >= 0");
    }
    if (minShare != null && minShare.signum() < 0) {
      throw new McpArgumentException("minShare must be >= 0");
    }

    CashflowParams params =
        new CashflowParams(
            dateFrom,
            dateTo,
            levels,
            exTransfers == null || exTransfers, // default true
            exPassthroughs == null || exPassthroughs, // default true
            Cashflow.InvestmentMode.fromWire(investment),
            topN == null ? 6 : topN,
            minShare == null ? BigDecimal.ZERO : minShare);
    return cashflowService.cashflow(params);
  }
}
