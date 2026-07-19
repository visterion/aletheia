package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DescribeSchemaIT extends AbstractPostgresIT {

  @Autowired ReadTools readTools;

  @Test
  void describesAllowlistedTablesOnly_noAuthTables_noDataRows() {
    var cols = readTools.describeSchema();
    var tables = cols.stream().map(SchemaColumn::table).distinct().toList();
    assertThat(tables)
        .containsExactlyInAnyOrder(
            "transactions",
            "counterparties",
            "counterparty_tags",
            "recurring",
            "contracts",
            "counterparty_history",
            "imports",
            "v_counterparty_evidence",
            "counterparty_alias",
            "cashflow_role_map");
    assertThat(tables)
        .doesNotContain("api_tokens", "oauth_tokens", "oauth_clients", "oauth_authorization_codes");
    // a known column with its curated description
    assertThat(cols)
        .filteredOn(c -> c.table().equals("transactions") && c.column().equals("direction"))
        .singleElement()
        .satisfies(c -> assertThat(c.description()).contains("DBIT"));
    // TP2 split columns documented for sql_query users (logical view explained in docs)
    assertThat(cols)
        .filteredOn(c -> c.table().equals("transactions") && c.column().equals("split_parent_content_hash"))
        .singleElement()
        .satisfies(c -> assertThat(c.description()).contains("split_parent").contains("logical view"));
    assertThat(cols)
        .filteredOn(c -> c.table().equals("transactions") && c.column().equals("split_parent_occurrence_index"))
        .singleElement()
        .satisfies(c -> assertThat(c.description()).contains("split_parent").contains("logical view"));
    // primary key flagged
    assertThat(cols)
        .filteredOn(c -> c.table().equals("counterparties") && c.column().equals("id"))
        .singleElement()
        .satisfies(c -> assertThat(c.primaryKey()).isTrue());
  }

  @Test
  void exposesV16Columns() {
    var cols = readTools.describeSchema();
    assertThat(cols)
        .anySatisfy(
            c -> {
              assertThat(c.table()).isEqualTo("counterparties");
              assertThat(c.column()).isEqualTo("display_name_override");
            })
        .anySatisfy(
            c -> {
              assertThat(c.table()).isEqualTo("contracts");
              assertThat(c.column()).isEqualTo("end_date");
            });
  }
}
