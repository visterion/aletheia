package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DescribeSchemaIT extends AbstractPostgresIT {

  @Autowired ReadTools readTools;
  @Autowired WriteTools writeTools;
  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_history, counterparty_tags, "
            + "counterparty_alias, tag_rules, counterparties, transactions, imports "
            + "RESTART IDENTITY CASCADE");
  }

  // --- fixture helpers (mirrors MergeCounterpartyIT's) ---

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private long insertCounterparty(String identityType, String identityValue) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, identityType)
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, identityValue)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void insertTxn(
      long importId, String contentHash, LocalDate bookingDate, String amount) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  private void insertSplitChild(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String parentHash,
      int parentOccurrenceIndex) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parentHash)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, parentOccurrenceIndex)
        .execute();
  }

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
            "cashflow_role_map",
            "product_rules");
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
  void exposesV19ProductColumns() {
    // Without these, an LLM writing sql_query cannot see the product grain at all and reads a
    // multi-product mandate as one lump -- which is the failure this slice exists to end.
    var cols = readTools.describeSchema(null).columns();
    assertThat(cols)
        .anySatisfy(
            c -> {
              assertThat(c.table()).isEqualTo("transactions");
              assertThat(c.column()).isEqualTo("product");
              assertThat(c.description()).contains("identity-normalised");
            })
        .anySatisfy(
            c -> {
              assertThat(c.table()).isEqualTo("transactions");
              assertThat(c.column()).isEqualTo("product_policy_no");
              // Columns are enumerated from information_schema; COLUMN_DOCS only decorates them,
              // so a table()/column() assertion alone passes with an empty description.
              assertThat(c.description()).contains("verbatim");
            })
        .anySatisfy(
            c -> {
              assertThat(c.table()).isEqualTo("contracts");
              assertThat(c.column()).isEqualTo("product");
              assertThat(c.description()).contains("NULLS NOT DISTINCT");
            });
  }

  @Test
  void productRulesIsDescribableOnItsOwn() {
    var result = readTools.describeSchema(List.of("product_rules"));

    assertThat(result.columns()).extracting(SchemaColumn::table).containsOnly("product_rules");
    assertThat(result.columns())
        .extracting(SchemaColumn::column)
        .contains("creditor_id", "position_pattern", "enabled", "roots_mismatched");
    assertThat(result.columns())
        .filteredOn(c -> c.column().equals("roots_mismatched"))
        .singleElement()
        .satisfies(c -> assertThat(c.description()).contains("sum"));
    assertThat(result.columns())
        .filteredOn(c -> c.column().equals("position_pattern"))
        .singleElement()
        .satisfies(c -> assertThat(c.description()).contains("ONE position"));
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
    // Guard-regression tripwire only (does the query parse/execute at all under
    // requireSelectOnly); it cannot catch an example that runs but teaches nothing, which is why
    // the two tests below assert on the actual returned numbers.
    DescribeSchemaResult result = readTools.describeSchema(null);
    assertThat(result.examples()).hasSize(3);
    for (String example : result.examples()) {
      assertThatCode(() -> readTools.sqlQuery(example)).doesNotThrowAnyException();
    }
  }

  @Test
  void example1ExcludesTheSplitParentAmount() {
    long imp = importId();
    String parentHash = "describe-schema-example1-parent";
    LocalDate booking = LocalDate.of(2030, 6, 15);
    // Parent alone would total 100.00; if the NOT EXISTS guard in example 1 failed to exclude it,
    // the monthly total would double to 200.00 instead of the children-only 100.00.
    insertTxn(imp, parentHash, booking, "100.00");
    insertSplitChild(imp, "describe-schema-example1-child-a", booking, "60.00", parentHash, 0);
    insertSplitChild(imp, "describe-schema-example1-child-b", booking, "40.00", parentHash, 0);

    String example1 = readTools.describeSchema(null).examples().get(0);
    SqlQueryResult result = readTools.sqlQuery(example1);

    assertThat(result.rows()).hasSize(1);
    assertThat(new BigDecimal(result.rows().get(0).get("total").toString()))
        .isEqualByComparingTo("100.00");
  }

  @Test
  void example2GroupsAMergedCounterpartysVariantsUnderTheCanonicalId() {
    long target = insertCounterparty("creditor_id", "DESCRIBE-SCHEMA-TARGET");
    long source = insertCounterparty("creditor_id", "DESCRIBE-SCHEMA-SOURCE");
    writeTools.mergeCounterparty(target, List.of(source), "describe_schema example fixture");

    String example2 = readTools.describeSchema(null).examples().get(1);
    SqlQueryResult result = readTools.sqlQuery(example2);

    // Both the target's own row and the folded source (still present, merged_into set, joined
    // back to the target through counterparty_alias) must pool under one effective_cp: variants
    // must reach 2, or the alias join taught nothing that a plain GROUP BY wouldn't already show.
    assertThat(result.rows())
        .anySatisfy(
            row -> assertThat(((Number) row.get("variants")).intValue()).isEqualTo(2));
  }
}
