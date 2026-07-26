package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DescribeSchemaIT extends AbstractPostgresIT {

  @Autowired ReadTools readTools;

  @Test
  void describesAllowlistedTablesOnly_noAuthTables_noDataRows() {
    var cols = readTools.describeSchema(null).columns();
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
    var cols = readTools.describeSchema(null).columns();
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

  @Test
  void tablesSubsetsTheColumnList() {
    DescribeSchemaResult all = readTools.describeSchema(null);
    DescribeSchemaResult subset = readTools.describeSchema(List.of("contracts", "recurring"));

    assertThat(subset.columns()).extracting(SchemaColumn::table)
        .containsOnly("contracts", "recurring");
    assertThat(subset.columns()).hasSizeLessThan(all.columns().size());
  }

  @Test
  void anEmptyTableListBehavesLikeNoArgument() {
    assertThat(readTools.describeSchema(List.of()).columns())
        .hasSameSizeAs(readTools.describeSchema(null).columns());
  }

  @Test
  void anUnknownTableNameFailsLoudlyAndNamesTheAllowedOnes() {
    // Silently returning an empty column list for a typo is worse than an error: the caller reads
    // "this table has no columns" and writes a query against a table that does not exist.
    assertThatThrownBy(() -> readTools.describeSchema(List.of("contract")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contract")
        .hasMessageContaining("contracts")
        .hasMessageContaining("transactions");
  }

  @Test
  void tableNamesAreCaseSensitive() {
    assertThatThrownBy(() -> readTools.describeSchema(List.of("Transactions")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyBundledExampleQueryActuallyRuns() {
    DescribeSchemaResult result = readTools.describeSchema(null);
    assertThat(result.examples()).hasSize(3);
    for (String example : result.examples()) {
      assertThatCode(() -> readTools.sqlQuery(example)).doesNotThrowAnyException();
    }
  }
}
