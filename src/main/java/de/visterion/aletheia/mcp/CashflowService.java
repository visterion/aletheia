package de.visterion.aletheia.mcp;

import de.visterion.aletheia.mcp.Cashflow.CashflowParams;
import de.visterion.aletheia.mcp.Cashflow.CashflowRole;
import de.visterion.aletheia.mcp.Cashflow.CashflowRow;
import de.visterion.aletheia.mcp.Cashflow.RoleKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * Loads the {@code cashflow_role_map}, fetches identity- and single-tag-resolved leaf
 * transactions over a date range, and delegates to {@link CashflowGraphBuilder} to produce a
 * balanced Sankey graph. See the cashflow Stage-2 plan for the SQL shape and the identity-CASE /
 * single-tag idiom reused from {@link ReadTools}.
 */
@Service
public class CashflowService {

  // language=SQL
  private static final String ROLE_MAP_SQL = "SELECT dimension, value, role FROM cashflow_role_map";

  private final DSLContext db;
  private final CashflowGraphBuilder builder;
  private final CashflowProperties properties;

  public CashflowService(DSLContext db, CashflowGraphBuilder builder, CashflowProperties properties) {
    this.db = db;
    this.builder = builder;
    this.properties = properties;
  }

  public Cashflow cashflow(CashflowParams params) {
    Map<RoleKey, CashflowRole> roleMap = loadRoleMap();
    List<CashflowRow> rows = fetchRows(params);
    return builder.build(rows, roleMap, Set.copyOf(properties.incomePayerIds()), params);
  }

  private Map<RoleKey, CashflowRole> loadRoleMap() {
    Map<RoleKey, CashflowRole> roleMap = new HashMap<>();
    for (Record row : db.fetch(ROLE_MAP_SQL)) {
      String dimension = row.get("dimension", String.class);
      String value = row.get("value", String.class);
      String role = row.get("role", String.class);
      roleMap.put(new RoleKey(dimension, value), CashflowRole.valueOf(role.toUpperCase(Locale.ROOT)));
    }
    return roleMap;
  }

  private List<CashflowRow> fetchRows(CashflowParams params) {
    String sql =
        "SELECT i.effective_cp,\n"
            + "       COALESCE(c.display_name_override, c.display_name) AS label,\n"
            + "       dtag.value AS domain_tag,\n"
            + "       ntag.value AS nature_tag,\n"
            + "       i.direction, i.amount, i.remittance_info, i.attribution_source,\n"
            + "       i.identity_value\n"
            + "FROM (\n"
            + ReadTools.identityResolvedTransactionsSql()
            + "\n) i\n"
            + "LEFT JOIN counterparties c ON c.id = i.effective_cp\n"
            + "LEFT JOIN (SELECT DISTINCT ON (counterparty_id) counterparty_id, value FROM counterparty_tags\n"
            + "           WHERE dimension = 'domain'\n"
            + "           ORDER BY counterparty_id, (source='confirmed') DESC, confidence DESC NULLS LAST, value) dtag\n"
            + "       ON dtag.counterparty_id = i.effective_cp\n"
            + "LEFT JOIN (SELECT DISTINCT ON (counterparty_id) counterparty_id, value FROM counterparty_tags\n"
            + "           WHERE dimension = 'nature'\n"
            + "           ORDER BY counterparty_id, (source='confirmed') DESC, confidence DESC NULLS LAST, value) ntag\n"
            + "       ON ntag.counterparty_id = i.effective_cp\n"
            + "WHERE i.booking_date BETWEEN ? AND ?";

    List<CashflowRow> rows = new ArrayList<>();
    for (Record row : db.fetch(sql, params.dateFrom(), params.dateTo())) {
      rows.add(
          new CashflowRow(
              row.get("effective_cp", Long.class),
              row.get("label", String.class),
              row.get("domain_tag", String.class),
              row.get("nature_tag", String.class),
              row.get("direction", String.class),
              row.get("amount", BigDecimal.class),
              row.get("remittance_info", String.class),
              row.get("attribution_source", String.class),
              row.get("identity_value", String.class)));
    }
    return rows;
  }
}
