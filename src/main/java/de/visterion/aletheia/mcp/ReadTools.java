package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.V_COUNTERPARTY_EVIDENCE;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * MCP read tools (spec §5 "Read", scope {@code read}). Every tool name here matches {@code
 * ToolPermissionService.READ_TOOLS} exactly.
 *
 * <p>All tools except {@link #sqlQuery} run on the app {@link DSLContext} ({@code db}, the
 * {@code @Primary} bean). {@link #sqlQuery} runs exclusively on {@code roDsl} (spec §6): a
 * prompt-injected query cannot write, even if the tool-layer SELECT-only check below is somehow
 * bypassed, because the underlying DB role is SELECT-only.
 */
@Component
public class ReadTools {

  private static final int DEFAULT_REVIEW_QUEUE_LIMIT = 50;

  /** The exact set of tables/views {@link #describeSchema()} exposes. Auth/oauth tables are
   * deliberately excluded (spec §6/§9): {@code sql_query} needs to discover the register/evidence
   * schema, never the auth schema. */
  private static final List<String> SCHEMA_TABLES =
      List.of(
          "transactions",
          "counterparties",
          "counterparty_tags",
          "recurring",
          "contracts",
          "counterparty_history",
          "imports",
          "v_counterparty_evidence");

  private static final Map<String, String> COLUMN_DOCS =
      Map.of(
          "transactions.direction",
              "DBIT (outgoing) | CRDT (incoming); amount is always positive",
          "transactions.content_hash", "SHA-256 idempotency natural key",
          "counterparties.identity_type", "creditor_id | iban | name",
          "counterparty_tags.dimension", "domain | nature | necessity (value is emergent/free)",
          "recurring.cadence", "monthly | quarterly | half_yearly | yearly | irregular",
          "v_counterparty_evidence.direction",
              "predominant direction across the counterparty's bookings");

  private static final Pattern SELECT_ONLY = Pattern.compile("(?is)^\\s*SELECT\\b.*");

  /**
   * Matches a top-level {@code INTO} keyword followed by whitespace and something else, the
   * shape used by {@code SELECT ... INTO new_table FROM ...} (table-creation, not a read). Applied
   * only to {@link #stripStringLiterals(String)} output so an {@code INTO} that only occurs inside
   * a string literal (e.g. {@code SELECT 'paid into account'}) does not trip the guard. An {@code
   * INTO} used as a mere identifier suffix (e.g. {@code AS into_total}) never matches because
   * {@code \bINTO\b} requires a non-word character on both sides.
   */
  private static final Pattern SELECT_INTO = Pattern.compile("(?is)\\bINTO\\b\\s+\\S");

  // language=SQL
  private static final String COUNTERPARTY_TRANSACTIONS_SQL =
      """
      SELECT i.id, i.booking_date, i.value_date, i.amount, i.currency, i.direction,
             i.booking_text, i.remittance_info, i.counterparty_name, i.counterparty_iban,
             i.creditor_id
      FROM (
          SELECT t.*,
              CASE
                  WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
                  WHEN t.counterparty_iban IS NOT NULL THEN 'iban'
                  WHEN t.counterparty_name IS NOT NULL THEN 'name'
              END AS identity_type,
              CASE
                  WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
                  WHEN t.counterparty_iban IS NOT NULL THEN t.counterparty_iban
                  WHEN t.counterparty_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.counterparty_name, NFC), '\\s+', ' ', 'g')))
              END AS identity_value
          FROM transactions t
      ) i
      JOIN counterparties c ON c.identity_type = i.identity_type AND c.identity_value = i.identity_value
      WHERE c.id = ?
        AND (CAST(? AS integer) IS NULL OR i.booking_date >= CURRENT_DATE - (CAST(? AS integer) * INTERVAL '1 day'))
      ORDER BY i.booking_date DESC
      """;

  private final DSLContext db;
  private final DSLContext roDsl;

  public ReadTools(DSLContext db, @Qualifier("roDsl") DSLContext roDsl) {
    this.db = db;
    this.roDsl = roDsl;
  }

  @Tool(
      name = "list_counterparties",
      description =
          "List counterparties with their evidence aggregates, current tags, recurring series"
              + " and contract-link status.")
  public List<CounterpartySummary> listCounterparties(
      @ToolParam(description = "default all", required = false) CounterpartyFilter filter,
      @ToolParam(description = "default spend_desc", required = false) CounterpartySort sort) {
    CounterpartyFilter effectiveFilter = filter == null ? CounterpartyFilter.all : filter;
    CounterpartySort effectiveSort = sort == null ? CounterpartySort.spend_desc : sort;

    var query =
        db.select(
                COUNTERPARTIES.ID,
                COUNTERPARTIES.DISPLAY_NAME,
                COUNTERPARTIES.IDENTITY_TYPE,
                COUNTERPARTIES.IDENTITY_VALUE,
                COUNTERPARTIES.STATUS,
                COUNTERPARTIES.REVIEWED,
                RECURRING.ID,
                RECURRING.CADENCE,
                RECURRING.TYPICAL_AMOUNT,
                RECURRING.AMOUNT_MIN,
                RECURRING.AMOUNT_MAX,
                RECURRING.FIRST_SEEN,
                RECURRING.LAST_SEEN,
                RECURRING.OCCURRENCE_COUNT,
                RECURRING.SOURCE,
                RECURRING.CONFIDENCE,
                V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID,
                V_COUNTERPARTY_EVIDENCE.TXN_COUNT,
                V_COUNTERPARTY_EVIDENCE.FIRST_SEEN,
                V_COUNTERPARTY_EVIDENCE.LAST_SEEN,
                V_COUNTERPARTY_EVIDENCE.SPAN_DAYS,
                V_COUNTERPARTY_EVIDENCE.TOTAL_AMOUNT,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_MIN,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_MAX,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_AVG,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_STDDEV,
                V_COUNTERPARTY_EVIDENCE.MEDIAN_GAP_DAYS,
                V_COUNTERPARTY_EVIDENCE.SPEND_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.DIRECTION,
                V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL,
                DSL.field(
                    DSL.exists(
                        DSL.selectOne()
                            .from(CONTRACTS)
                            .where(CONTRACTS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))))
                    .as("has_contract"))
            .from(COUNTERPARTIES)
            .leftJoin(RECURRING)
            .on(RECURRING.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID));

    var conditionalQuery =
        switch (effectiveFilter) {
          case untagged ->
              query.where(
                  DSL.notExists(
                      DSL.selectOne()
                          .from(COUNTERPARTY_TAGS)
                          .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))));
          case unreviewed -> query.where(COUNTERPARTIES.REVIEWED.eq(false));
          case has_recurring -> query.where(RECURRING.ID.isNotNull());
          case all -> query.where(DSL.trueCondition());
        };

    var sortedQuery =
        switch (effectiveSort) {
          case recent -> conditionalQuery.orderBy(V_COUNTERPARTY_EVIDENCE.LAST_SEEN.desc().nullsLast());
          case spend_desc ->
              conditionalQuery.orderBy(V_COUNTERPARTY_EVIDENCE.SPEND_LAST_365D.desc().nullsLast());
        };

    Result<Record> rows = sortedQuery.fetch();

    Map<Long, List<CounterpartyTagView>> tagsByCounterparty = fetchTagsByCounterparty();

    List<CounterpartySummary> result = new ArrayList<>();
    for (Record row : rows) {
      long id = row.get(COUNTERPARTIES.ID);
      result.add(
          new CounterpartySummary(
              id,
              row.get(COUNTERPARTIES.DISPLAY_NAME),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(COUNTERPARTIES.IDENTITY_VALUE),
              row.get(COUNTERPARTIES.STATUS),
              Boolean.TRUE.equals(row.get(COUNTERPARTIES.REVIEWED)),
              mapEvidence(row, id),
              tagsByCounterparty.getOrDefault(id, List.of()),
              mapRecurring(row),
              Boolean.TRUE.equals(row.get("has_contract", Boolean.class))));
    }
    return result;
  }

  @Tool(
      name = "get_review_queue",
      description =
          "The counterparties still needing a human decision (status='open'), ordered"
              + " descending by estimated annual cost (recurring.typical_amount * periods/year,"
              + " or spend_last_365d if no recurring series is recorded yet).")
  public List<ReviewQueueEntry> getReviewQueue(
      @ToolParam(description = "max rows to return (default 50)", required = false)
          Integer limit) {
    int effectiveLimit = limit != null && limit > 0 ? limit : DEFAULT_REVIEW_QUEUE_LIMIT;

    var rows =
        db.select(
                COUNTERPARTIES.ID,
                COUNTERPARTIES.DISPLAY_NAME,
                COUNTERPARTIES.IDENTITY_TYPE,
                RECURRING.ID,
                RECURRING.CADENCE,
                RECURRING.TYPICAL_AMOUNT,
                RECURRING.AMOUNT_MIN,
                RECURRING.AMOUNT_MAX,
                RECURRING.FIRST_SEEN,
                RECURRING.LAST_SEEN,
                RECURRING.OCCURRENCE_COUNT,
                RECURRING.SOURCE,
                RECURRING.CONFIDENCE,
                V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID,
                V_COUNTERPARTY_EVIDENCE.TXN_COUNT,
                V_COUNTERPARTY_EVIDENCE.FIRST_SEEN,
                V_COUNTERPARTY_EVIDENCE.LAST_SEEN,
                V_COUNTERPARTY_EVIDENCE.SPAN_DAYS,
                V_COUNTERPARTY_EVIDENCE.TOTAL_AMOUNT,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_MIN,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_MAX,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_AVG,
                V_COUNTERPARTY_EVIDENCE.AMOUNT_STDDEV,
                V_COUNTERPARTY_EVIDENCE.MEDIAN_GAP_DAYS,
                V_COUNTERPARTY_EVIDENCE.SPEND_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.DIRECTION,
                V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL)
            .from(COUNTERPARTIES)
            .leftJoin(RECURRING)
            .on(RECURRING.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .where(COUNTERPARTIES.STATUS.eq("open"))
            .fetch();

    List<ReviewQueueEntry> entries = new ArrayList<>();
    for (Record row : rows) {
      long id = row.get(COUNTERPARTIES.ID);
      CounterpartyEvidence evidence = mapEvidence(row, id);
      RecurringView recurring = mapRecurring(row);
      entries.add(
          new ReviewQueueEntry(
              id,
              row.get(COUNTERPARTIES.DISPLAY_NAME),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              evidence,
              recurring,
              AnnualCost.estimate(recurring, evidence)));
    }

    entries.sort(Comparator.comparing(ReviewQueueEntry::annualCostEstimate).reversed());
    return entries.size() > effectiveLimit ? entries.subList(0, effectiveLimit) : entries;
  }

  @Tool(
      name = "list_unmatched_recurring",
      description =
          "Recurring series whose counterparty has no linked contracts row -- a recurring debit"
              + " without a documented contract.")
  public List<UnmatchedRecurringEntry> listUnmatchedRecurring() {
    var rows =
        db.select(
                COUNTERPARTIES.ID,
                COUNTERPARTIES.DISPLAY_NAME,
                COUNTERPARTIES.IDENTITY_TYPE,
                COUNTERPARTIES.IDENTITY_VALUE,
                RECURRING.ID,
                RECURRING.CADENCE,
                RECURRING.TYPICAL_AMOUNT,
                RECURRING.AMOUNT_MIN,
                RECURRING.AMOUNT_MAX,
                RECURRING.FIRST_SEEN,
                RECURRING.LAST_SEEN,
                RECURRING.OCCURRENCE_COUNT,
                RECURRING.SOURCE,
                RECURRING.CONFIDENCE)
            .from(RECURRING)
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(RECURRING.COUNTERPARTY_ID))
            .where(
                DSL.notExists(
                    DSL.selectOne()
                        .from(CONTRACTS)
                        .where(CONTRACTS.COUNTERPARTY_ID.eq(RECURRING.COUNTERPARTY_ID))))
            .fetch();

    List<UnmatchedRecurringEntry> entries = new ArrayList<>();
    for (Record row : rows) {
      entries.add(
          new UnmatchedRecurringEntry(
              row.get(COUNTERPARTIES.ID),
              row.get(COUNTERPARTIES.DISPLAY_NAME),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(COUNTERPARTIES.IDENTITY_VALUE),
              mapRecurring(row)));
    }
    return entries;
  }

  @Tool(
      name = "counterparty_transactions",
      description =
          "The underlying bookings for one counterparty (evidence detail), optionally limited to"
              + " the last N days via period.")
  public List<TransactionView> counterpartyTransactions(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(description = "restrict to the last N days; omit for all history", required = false)
          Integer period) {
    Result<Record> rows =
        db.fetch(COUNTERPARTY_TRANSACTIONS_SQL, counterpartyId, period, period);
    List<TransactionView> transactions = new ArrayList<>();
    for (Record row : rows) {
      transactions.add(
          new TransactionView(
              row.get("id", Long.class),
              row.get("booking_date", java.time.LocalDate.class),
              row.get("value_date", java.time.LocalDate.class),
              row.get("amount", BigDecimal.class),
              row.get("currency", String.class),
              row.get("direction", String.class),
              row.get("booking_text", String.class),
              row.get("remittance_info", String.class),
              row.get("counterparty_name", String.class),
              row.get("counterparty_iban", String.class),
              row.get("creditor_id", String.class)));
    }
    return transactions;
  }

  @Tool(
      name = "taxonomy",
      description =
          "The emergent tag vocabulary already in use, per dimension (domain|nature|necessity),"
              + " with counts -- reuse these values instead of inventing synonyms.")
  public List<TaxonomyDimension> taxonomy() {
    var rows =
        db.select(
                COUNTERPARTY_TAGS.DIMENSION,
                COUNTERPARTY_TAGS.VALUE,
                DSL.count().as("value_count"))
            .from(COUNTERPARTY_TAGS)
            .groupBy(COUNTERPARTY_TAGS.DIMENSION, COUNTERPARTY_TAGS.VALUE)
            .orderBy(COUNTERPARTY_TAGS.DIMENSION, DSL.field("value_count", Integer.class).desc())
            .fetch();

    Map<String, List<TaxonomyValue>> valuesByDimension = new LinkedHashMap<>();
    for (var row : rows) {
      valuesByDimension
          .computeIfAbsent(row.get(COUNTERPARTY_TAGS.DIMENSION), key -> new ArrayList<>())
          .add(
              new TaxonomyValue(
                  row.get(COUNTERPARTY_TAGS.VALUE), row.get("value_count", Integer.class)));
    }

    List<TaxonomyDimension> dimensions = new ArrayList<>();
    for (var entry : valuesByDimension.entrySet()) {
      dimensions.add(new TaxonomyDimension(entry.getKey(), entry.getValue()));
    }
    return dimensions;
  }

  @Tool(
      name = "sql_query",
      description =
          "Read-only escape hatch: run an arbitrary SELECT against the register/evidence schema."
              + " Runs on a SELECT-only DB role; non-SELECT, stacked, and SELECT INTO statements"
              + " are rejected before execution.")
  public SqlQueryResult sqlQuery(@ToolParam(description = "a single SELECT statement") String sql) {
    requireSelectOnly(sql);
    Result<Record> result = roDsl.fetch(sql);
    List<String> columns = new ArrayList<>();
    for (Field<?> field : result.fields()) {
      columns.add(field.getName());
    }
    List<Map<String, Object>> rowMaps = new ArrayList<>();
    for (Record row : result) {
      Map<String, Object> rowMap = new LinkedHashMap<>();
      for (String column : columns) {
        rowMap.put(column, row.get(column));
      }
      rowMaps.add(rowMap);
    }
    return new SqlQueryResult(columns, rowMaps);
  }

  @Tool(
      name = "describe_schema",
      description =
          "Structure of the register/evidence schema (tables, columns, types, keys) so sql_query"
              + " can be written without guessing. No data rows.")
  public List<SchemaColumn> describeSchema() {
    Set<List<String>> primaryKeys = fetchKeyColumns("PRIMARY KEY");
    Set<List<String>> foreignKeys = fetchKeyColumns("FOREIGN KEY");

    var columnRows =
        db.select(
                DSL.field("table_name", String.class),
                DSL.field("column_name", String.class),
                DSL.field("data_type", String.class),
                DSL.field("is_nullable", String.class))
            .from(DSL.table("information_schema.columns"))
            .where(DSL.field("table_schema", String.class).eq("public"))
            .and(DSL.field("table_name", String.class).in(SCHEMA_TABLES))
            .orderBy(DSL.field("table_name"), DSL.field("ordinal_position"))
            .fetch();

    List<SchemaColumn> columns = new ArrayList<>();
    for (var row : columnRows) {
      String table = row.get("table_name", String.class);
      String column = row.get("column_name", String.class);
      List<String> key = List.of(table, column);
      columns.add(
          new SchemaColumn(
              table,
              column,
              row.get("data_type", String.class),
              "YES".equals(row.get("is_nullable", String.class)),
              primaryKeys.contains(key),
              foreignKeys.contains(key),
              COLUMN_DOCS.get(table + "." + column)));
    }
    return columns;
  }

  private Set<List<String>> fetchKeyColumns(String constraintType) {
    var rows =
        db.select(
                DSL.field("tc.table_name", String.class), DSL.field("kcu.column_name", String.class))
            .from(DSL.table("information_schema.table_constraints").as("tc"))
            .join(DSL.table("information_schema.key_column_usage").as("kcu"))
            .on(DSL.field("tc.constraint_name", String.class)
                .eq(DSL.field("kcu.constraint_name", String.class)))
            .where(DSL.field("tc.constraint_type", String.class).eq(constraintType))
            .and(DSL.field("tc.table_name", String.class).in(SCHEMA_TABLES))
            .fetch();
    Set<List<String>> keys = new HashSet<>();
    for (var row : rows) {
      keys.add(
          List.of(
              row.get("tc.table_name", String.class), row.get("kcu.column_name", String.class)));
    }
    return keys;
  }

  /**
   * Rejects anything that is not a single, side-effect-free {@code SELECT} statement, before the
   * tool ever touches the (SELECT-only) {@code roDsl} connection (spec §5/§9): non-SELECT
   * statements (INSERT, UPDATE, DELETE, DDL, ...), stacked statements (a {@code ;} followed by
   * more SQL), and the {@code SELECT ... INTO ...} table-creation form (a SELECT that is
   * actually a write: it creates and populates a new table).
   *
   * <p><b>This is defense-in-depth, not the security boundary.</b> The real boundary is the
   * {@code aletheia_ro} DB role backing {@link #roDsl}: it has {@code SELECT} only, no {@code
   * CREATE}, and no {@code EXECUTE} on dangerous functions. This tool-layer guard catches the
   * obvious shapes (non-SELECT, stacked statements, {@code SELECT INTO}) early with a clear error
   * message, but it cannot catch a side-effecting function call hidden inside an otherwise
   * syntactically valid SELECT (e.g. {@code SELECT pg_sleep(10)} or {@code SELECT
   * lo_export(...)}) -- blocking those is the DB role's job, not a regex's.
   */
  private static void requireSelectOnly(String sql) {
    if (sql == null || sql.isBlank()) {
      throw new IllegalArgumentException("sql_query requires a non-blank SELECT statement");
    }
    String trimmed = sql.strip();
    String withoutTrailingSemicolon =
        trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    if (withoutTrailingSemicolon.contains(";")) {
      throw new IllegalArgumentException("sql_query rejects stacked statements");
    }
    if (!SELECT_ONLY.matcher(withoutTrailingSemicolon).matches()) {
      throw new IllegalArgumentException("sql_query only allows a single SELECT statement");
    }
    if (SELECT_INTO.matcher(stripStringLiterals(withoutTrailingSemicolon)).find()) {
      throw new IllegalArgumentException(
          "sql_query rejects SELECT INTO (it creates/populates a table, not a read)");
    }
  }

  /**
   * Blanks out the contents of single-quoted SQL string literals so guard regexes only ever see
   * actual SQL syntax, not text a caller put inside a string (e.g. {@code SELECT 'paid into
   * account'} must not trip the {@code SELECT INTO} guard). Not a full SQL tokenizer: it toggles
   * on every {@code '}, so the standard {@code ''} escaped-quote form is treated as an empty
   * string followed by the resumption of literal SQL rather than one literal containing a quote,
   * and double-quoted identifiers are left as-is. Both are accepted, documented gaps -- {@link
   * #requireSelectOnly} is defense-in-depth, not the security boundary.
   */
  private static String stripStringLiterals(String sql) {
    StringBuilder result = new StringBuilder(sql.length());
    boolean inString = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'') {
        inString = !inString;
        result.append(' ');
      } else {
        result.append(inString ? ' ' : c);
      }
    }
    return result.toString();
  }

  private Map<Long, List<CounterpartyTagView>> fetchTagsByCounterparty() {
    Map<Long, List<CounterpartyTagView>> byCounterparty = new LinkedHashMap<>();
    db.selectFrom(COUNTERPARTY_TAGS)
        .fetch()
        .forEach(
            tagRow ->
                byCounterparty
                    .computeIfAbsent(tagRow.get(COUNTERPARTY_TAGS.COUNTERPARTY_ID), key -> new ArrayList<>())
                    .add(
                        new CounterpartyTagView(
                            tagRow.get(COUNTERPARTY_TAGS.DIMENSION),
                            tagRow.get(COUNTERPARTY_TAGS.VALUE),
                            tagRow.get(COUNTERPARTY_TAGS.SOURCE),
                            tagRow.get(COUNTERPARTY_TAGS.CONFIDENCE))));
    return byCounterparty;
  }

  /** Returns {@code null} when the left-joined evidence row is absent (no matched transactions). */
  static CounterpartyEvidence mapEvidence(Record row, long counterpartyId) {
    Long evidenceCounterpartyId = row.get(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID);
    if (evidenceCounterpartyId == null) {
      return null;
    }
    Long txnCount = row.get(V_COUNTERPARTY_EVIDENCE.TXN_COUNT);
    Integer spanDays = row.get(V_COUNTERPARTY_EVIDENCE.SPAN_DAYS);
    return new CounterpartyEvidence(
        counterpartyId,
        txnCount == null ? 0 : txnCount.intValue(),
        row.get(V_COUNTERPARTY_EVIDENCE.FIRST_SEEN),
        row.get(V_COUNTERPARTY_EVIDENCE.LAST_SEEN),
        spanDays == null ? 0 : spanDays,
        row.get(V_COUNTERPARTY_EVIDENCE.TOTAL_AMOUNT),
        row.get(V_COUNTERPARTY_EVIDENCE.AMOUNT_MIN),
        row.get(V_COUNTERPARTY_EVIDENCE.AMOUNT_MAX),
        row.get(V_COUNTERPARTY_EVIDENCE.AMOUNT_AVG),
        row.get(V_COUNTERPARTY_EVIDENCE.AMOUNT_STDDEV),
        row.get(V_COUNTERPARTY_EVIDENCE.MEDIAN_GAP_DAYS),
        row.get(V_COUNTERPARTY_EVIDENCE.SPEND_LAST_365D),
        row.get(V_COUNTERPARTY_EVIDENCE.DIRECTION),
        row.get(V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D),
        row.get(V_COUNTERPARTY_EVIDENCE.CREDIT_LAST_365D),
        row.get(V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL));
  }

  /** Returns {@code null} when the left-joined recurring row is absent. */
  static RecurringView mapRecurring(Record row) {
    Long recurringId = row.get(RECURRING.ID);
    if (recurringId == null) {
      return null;
    }
    return new RecurringView(
        recurringId,
        row.get(RECURRING.CADENCE),
        row.get(RECURRING.TYPICAL_AMOUNT),
        row.get(RECURRING.AMOUNT_MIN),
        row.get(RECURRING.AMOUNT_MAX),
        row.get(RECURRING.FIRST_SEEN),
        row.get(RECURRING.LAST_SEEN),
        row.get(RECURRING.OCCURRENCE_COUNT),
        row.get(RECURRING.SOURCE),
        row.get(RECURRING.CONFIDENCE));
  }
}
