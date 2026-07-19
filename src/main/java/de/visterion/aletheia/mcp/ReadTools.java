package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_ALIAS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.V_CONTRACT_EVIDENCE;
import static de.visterion.aletheia.jooq.Tables.V_COUNTERPARTY_EVIDENCE;

import de.visterion.aletheia.substrate.CounterpartyEvidence;
import de.visterion.aletheia.substrate.TransactionLayerSql;
import de.visterion.aletheia.tagrules.TagRuleResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * MCP read tools (spec §5 "Read", scope {@code read}). Every tool name here matches {@code
 * ToolPermissionService.READ_TOOLS} exactly.
 *
 * <p>Business read paths implement the TP2 logical view over transactions: split parents that
 * have children are excluded via NOT EXISTS on split_parent_content_hash/occurrence_index.
 * Only current leaf positions (children and unsplit originals) are visible to aggregate,
 * counterparty_transactions, evidence-backed tools (list_counterparties, obligations_register,
 * get_review_queue, list_income etc.). {@link #sqlQuery} and raw table access see the full
 * physical rows.
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
          "v_counterparty_evidence",
          "counterparty_alias");

  private static final Map<String, String> COLUMN_DOCS =
      Map.of(
          "transactions.direction",
              "DBIT (outgoing) | CRDT (incoming); amount is always positive",
          "transactions.content_hash", "SHA-256 idempotency natural key",
          "transactions.split_parent_content_hash",
              "split_parent_* set on children only (backref); logical view (NOT EXISTS) excludes parents that have children",
          "transactions.split_parent_occurrence_index",
              "split_parent_* set on children only (backref); logical view (NOT EXISTS) excludes parents that have children",
          "counterparties.identity_type", "creditor_id | iban | name",
          "counterparty_tags.dimension", "domain | nature | necessity (value is emergent/free)",
          "recurring.cadence", "monthly | quarterly | half_yearly | yearly | irregular",
          "v_counterparty_evidence.direction",
              "predominant direction across the counterparty's bookings",
          "counterparties.display_name_override",
              "Manual display-name override (set_display_name); wins over the derived display_name at read time. Never affects identity.",
          "contracts.end_date", "Date an ended obligation stopped (status='ended'); NULL while active.");

  /**
   * The read-time effective display name (P2 manual override, Spec B): every read that surfaces a
   * counterparty label selects this instead of the bare {@code display_name} column, so a manual
   * {@code display_name_override} wins without ever touching identity resolution. Aliased {@code
   * display_name} so downstream {@code row.get("display_name", ...)}/{@code row.get(FIELD)} reads
   * are unaffected.
   */
  private static final Field<String> DISPLAY_NAME_EFFECTIVE =
      DSL.coalesce(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE, COUNTERPARTIES.DISPLAY_NAME)
          .as("display_name");

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
             i.creditor_id, i.content_hash, i.occurrence_index
      FROM (
          SELECT t.*,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN 'name'
                  WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
                  WHEN t.counterparty_iban IS NOT NULL THEN 'iban'
                  WHEN t.counterparty_name IS NOT NULL THEN 'name'
              END AS identity_type,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.attributed_name, NFC), '\\s+', ' ', 'g')))
                  WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
                  WHEN t.counterparty_iban IS NOT NULL THEN t.counterparty_iban
                  WHEN t.counterparty_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.counterparty_name, NFC), '\\s+', ' ', 'g')))
              END AS identity_value
          FROM transactions t
          """
          + " WHERE "
          + TransactionLayerSql.notExistsSupersededParent("t")
          + "\n"
          + """
      ) i
      LEFT JOIN counterparty_alias al ON al.identity_type = i.identity_type AND al.identity_value = i.identity_value
      LEFT JOIN counterparties own ON own.identity_type = i.identity_type AND own.identity_value = i.identity_value
      WHERE COALESCE(al.canonical_counterparty_id, own.id) = ?
        AND (
          (CAST(? AS date) IS NOT NULL AND CAST(? AS date) IS NOT NULL
              AND i.booking_date BETWEEN CAST(? AS date) AND CAST(? AS date))
          OR ((CAST(? AS date) IS NULL OR CAST(? AS date) IS NULL)
              AND (CAST(? AS integer) IS NULL
                  OR i.booking_date >= CURRENT_DATE - (CAST(? AS integer) * INTERVAL '1 day')))
        )
      ORDER BY i.booking_date DESC
      """;

  /**
   * The identity-CASE derived-table body reused by {@link #aggregate} (spec §5, M-1 join-scope
   * rule): copied from the {@code i} subquery inside {@link #COUNTERPARTY_TRANSACTIONS_SQL}
   * rather than shared, so a future change to one read path cannot silently change the other's
   * join semantics.
   */
  // language=SQL
  private static final String IDENTITY_RESOLVED_TRANSACTIONS_SQL =
      """
      SELECT ident.*, COALESCE(al.canonical_counterparty_id, own.id) AS effective_cp
      FROM (
          SELECT t.*,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN 'name'
                  WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
                  WHEN t.counterparty_iban IS NOT NULL THEN 'iban'
                  WHEN t.counterparty_name IS NOT NULL THEN 'name'
          END AS identity_type,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.attributed_name, NFC), '\\s+', ' ', 'g')))
                  WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
                  WHEN t.counterparty_iban IS NOT NULL THEN t.counterparty_iban
                  WHEN t.counterparty_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.counterparty_name, NFC), '\\s+', ' ', 'g')))
              END AS identity_value
          FROM transactions t
          """
          + " WHERE "
          + TransactionLayerSql.notExistsSupersededParent("t")
          + "\n"
          + """
      ) ident
      LEFT JOIN counterparty_alias al ON al.identity_type = ident.identity_type AND al.identity_value = ident.identity_value
      LEFT JOIN counterparties own ON own.identity_type = ident.identity_type AND own.identity_value = ident.identity_value
      """;

  private final DSLContext db;
  private final DSLContext roDsl;
  private final CounterpartySelectorResolver selectorResolver;
  private final OperatingGuideService operatingGuideService;
  private final TagRuleResolver tagRuleResolver;

  public ReadTools(
      DSLContext db,
      @Qualifier("roDsl") DSLContext roDsl,
      CounterpartySelectorResolver selectorResolver,
      OperatingGuideService operatingGuideService,
      TagRuleResolver tagRuleResolver) {
    this.db = db;
    this.roDsl = roDsl;
    this.selectorResolver = selectorResolver;
    this.operatingGuideService = operatingGuideService;
    this.tagRuleResolver = tagRuleResolver;
  }

  public String wakeUp() {
    return operatingGuideService.wakeUp();
  }

  public List<TagRuleView> listTagRules() {
    return tagRuleResolver.loadEnabledRulesIncludingDisabled().stream()
        .map(
            r ->
                new TagRuleView(
                    r.id(), r.name(), r.enabled(), r.conditions(), r.actions(), null))
        .toList();
  }

  public List<AggregateBucket> aggregate(
      LocalDate dateFrom,
      LocalDate dateTo,
      AggregateGroupBy groupBy,
      AggregateMetric metric,
      Direction direction,
      Boolean byCounterparty,
      List<Long> counterpartyIds,
      CounterpartySelector where) {
    boolean effectiveByCounterparty = Boolean.TRUE.equals(byCounterparty);
    List<Long> ids = resolveAggregateScope(counterpartyIds, where);
    boolean joinIdentity = ids != null || effectiveByCounterparty;
    if (joinIdentity && ids != null && ids.isEmpty()) {
      return List.of();
    }

    String txnAlias = joinIdentity ? "i" : "t";
    String dateRef = txnAlias + ".booking_date";
    String directionRef = txnAlias + ".direction";
    String amountRef = txnAlias + ".amount";

    String period = periodExpr(groupBy, dateRef);
    String amountExpr = signedAmountExpr(direction, amountRef, directionRef);
    String valueExpr = "CAST(" + valueExpr(metric, amountExpr) + " AS numeric)";

    StringBuilder sql = new StringBuilder("SELECT ").append(period).append(" AS period");
    if (effectiveByCounterparty) {
      sql.append(
          ", c.id AS counterparty_id, "
              + "COALESCE(c.display_name_override, c.display_name) AS display_name");
    }
    sql.append(", ").append(valueExpr).append(" AS value ");

    if (joinIdentity) {
      sql.append("FROM (")
          .append(IDENTITY_RESOLVED_TRANSACTIONS_SQL)
          .append(") i JOIN counterparties c ON c.id = i.effective_cp ");
    } else {
      sql.append("FROM transactions t ");
    }

    List<Object> binds = new ArrayList<>();
    sql.append("WHERE ").append(dateRef).append(" BETWEEN ? AND ? ");
    binds.add(dateFrom);
    binds.add(dateTo);

    if (direction != Direction.BOTH) {
      sql.append("AND ").append(directionRef).append(" = ? ");
      binds.add(direction.name());
    }

    if (joinIdentity && ids != null) {
      sql.append("AND c.id IN (")
          .append(ids.stream().map(id -> "?").collect(Collectors.joining(",")))
          .append(") ");
      binds.addAll(ids);
    }

    // When joinIdentity: IDENTITY subselect already applied the filter — do not double-append.
    if (!joinIdentity) {
      sql.append("AND ")
          .append(TransactionLayerSql.notExistsSupersededParent(txnAlias))
          .append(" ");
    }

    // A TOTAL period is the string literal 'total', not a real column expression -- Postgres
    // rejects a bare string constant in GROUP BY (same rule as ORDER BY above), and it doesn't
    // need to be there anyway: a constant in the SELECT list is always allowed regardless of
    // GROUP BY. So the period expression is only added to GROUP BY for MONTH/QUARTER/YEAR.
    List<String> groupByCols = new ArrayList<>();
    if (groupBy != AggregateGroupBy.TOTAL) {
      groupByCols.add(period);
    }
    if (effectiveByCounterparty) {
      groupByCols.add("c.id");
      groupByCols.add("COALESCE(c.display_name_override, c.display_name)");
    }
    if (!groupByCols.isEmpty()) {
      sql.append("GROUP BY ").append(String.join(", ", groupByCols)).append(' ');
    }

    // Order by the "period" output name, not the raw expression: a TOTAL period is the string
    // literal 'total', and Postgres rejects a bare string constant in ORDER BY (it looks for an
    // integer ordinal), while an output-column-name reference works for any expression.
    sql.append("ORDER BY period");
    if (effectiveByCounterparty) {
      sql.append(", c.id");
    }

    Result<Record> rows = db.fetch(sql.toString(), binds.toArray());
    List<AggregateBucket> buckets = new ArrayList<>();
    for (Record row : rows) {
      buckets.add(
          new AggregateBucket(
              row.get("period", String.class),
              effectiveByCounterparty ? row.get("counterparty_id", Long.class) : null,
              effectiveByCounterparty ? row.get("display_name", String.class) : null,
              row.get("value", BigDecimal.class)));
    }
    return buckets;
  }

  /**
   * M-1 join-scope resolution: {@code counterpartyIds} wins when non-null and non-empty; else
   * {@code where} is resolved via {@link CounterpartySelectorResolver}; else {@code null}
   * (unscoped -- see {@link #aggregate} for what that means for the join).
   */
  private List<Long> resolveAggregateScope(List<Long> counterpartyIds, CounterpartySelector where) {
    if (counterpartyIds != null && !counterpartyIds.isEmpty()) {
      return counterpartyIds;
    }
    if (where != null) {
      return selectorResolver.resolve(where);
    }
    return null;
  }

  private static String periodExpr(AggregateGroupBy groupBy, String dateColumnRef) {
    if (groupBy == AggregateGroupBy.TOTAL) {
      return "'total'";
    }
    String unit = groupBy.name().toLowerCase(java.util.Locale.ROOT);
    return "to_char(date_trunc('" + unit + "', " + dateColumnRef + "), 'YYYY-MM-DD')";
  }

  /** BOTH nets a signed amount (DBIT negated); a single direction uses the positive amount as-is
   * (the direction filter already restricts rows to that direction). */
  private static String signedAmountExpr(Direction direction, String amountRef, String directionRef) {
    if (direction == Direction.BOTH) {
      return "CASE WHEN " + directionRef + " = 'DBIT' THEN -" + amountRef + " ELSE " + amountRef + " END";
    }
    return amountRef;
  }

  /**
   * SUM and COUNT are wrapped in {@code COALESCE(..., 0)} so an empty range (or an empty group)
   * reads as zero, matching a charting tool's expectation of "nothing happened" rather than
   * {@code null}. AVG and MEDIAN are deliberately left as-is: an average/median over an empty set
   * has no defined value, so {@code null} is the correct answer there.
   */
  private static String valueExpr(AggregateMetric metric, String amountExpr) {
    return switch (metric) {
      case SUM -> "COALESCE(sum(" + amountExpr + "), 0)";
      case AVG -> "avg(" + amountExpr + ")";
      case MEDIAN -> "percentile_cont(0.5) WITHIN GROUP (ORDER BY " + amountExpr + ")";
      case COUNT -> "COALESCE(count(*), 0)";
    };
  }

  public List<CounterpartySummary> listCounterparties(
      CounterpartyFilter filter,
      CounterpartySort sort) {
    CounterpartyFilter effectiveFilter = filter == null ? CounterpartyFilter.all : filter;
    CounterpartySort effectiveSort = sort == null ? CounterpartySort.spend_desc : sort;

    var query =
        db.select(
                COUNTERPARTIES.ID,
                DISPLAY_NAME_EFFECTIVE,
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
                    .as("has_contract"),
                DSL.field(
                        DSL.select(DSL.count())
                            .from(CONTRACTS)
                            .where(CONTRACTS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID)))
                    .as("contract_count"))
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
                          .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID)))
                      .and(COUNTERPARTIES.MERGED_INTO.isNull()));
          case unreviewed ->
              query.where(
                  COUNTERPARTIES.REVIEWED.eq(false).and(COUNTERPARTIES.MERGED_INTO.isNull()));
          case has_recurring ->
              query.where(
                  RECURRING.ID.isNotNull().and(COUNTERPARTIES.MERGED_INTO.isNull()));
          case all -> query.where(COUNTERPARTIES.MERGED_INTO.isNull());
        };

    var sortedQuery =
        switch (effectiveSort) {
          case recent -> conditionalQuery.orderBy(V_COUNTERPARTY_EVIDENCE.LAST_SEEN.desc().nullsLast());
          case spend_desc ->
              conditionalQuery.orderBy(V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D.desc().nullsLast());
        };

    Result<Record> rows = sortedQuery.fetch();

    Map<Long, List<CounterpartyTagView>> tagsByCounterparty = fetchTagsByCounterparty();
    Map<Long, List<CounterpartyAliasView>> aliasesByCounterparty = fetchAliasesByCounterparty();

    // A counterparty may now have multiple `recurring` rows (one per contract, TP1) -- the
    // leftJoin(RECURRING) above fans a split counterparty into multiple rows here. Keep only the
    // first row seen per counterparty id so each counterparty is returned exactly once; the
    // dropped duplicate rows carry the same evidence/tags/contract_count (those aren't
    // recurring-scoped), only `recurring` differs, and a single representative series is enough
    // for this summary (spec §5 -- the full set is available via obligations_register/
    // list_unmatched_recurring at contract grain).
    Set<Long> seenIds = new HashSet<>();
    List<CounterpartySummary> result = new ArrayList<>();
    for (Record row : rows) {
      long id = row.get(COUNTERPARTIES.ID);
      if (!seenIds.add(id)) {
        continue;
      }
      Integer contractCount = row.get("contract_count", Integer.class);
      result.add(
          new CounterpartySummary(
              id,
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(COUNTERPARTIES.IDENTITY_VALUE),
              row.get(COUNTERPARTIES.STATUS),
              Boolean.TRUE.equals(row.get(COUNTERPARTIES.REVIEWED)),
              mapEvidence(row, id),
              tagsByCounterparty.getOrDefault(id, List.of()),
              mapRecurring(row),
              Boolean.TRUE.equals(row.get("has_contract", Boolean.class)),
              contractCount == null ? 0 : contractCount,
              aliasesByCounterparty.getOrDefault(id, List.of())));
    }
    return result;
  }

  public List<ReviewQueueEntry> getReviewQueue(
      Integer limit,
      Boolean verbose) {
    int effectiveLimit = limit != null && limit > 0 ? limit : DEFAULT_REVIEW_QUEUE_LIMIT;
    boolean effectiveVerbose = Boolean.TRUE.equals(verbose);

    List<ReviewQueueEntry> entries = new ArrayList<>();
    entries.addAll(openContractEntries(effectiveVerbose));
    entries.addAll(openCounterpartiesWithoutContractsEntries(effectiveVerbose));

    entries.sort(Comparator.comparing(ReviewQueueEntry::annualCostEstimate).reversed());
    return entries.size() > effectiveLimit ? entries.subList(0, effectiveLimit) : entries;
  }

  /**
   * The primary decision unit (TP1 contract grain): one row per OPEN {@code contracts} row.
   * {@code v_contract_evidence} supplies the per-mandate debit fallback; {@code
   * v_counterparty_evidence} is left-joined too so a mandate-less contract (whose {@code
   * v_contract_evidence} join yields no match, since {@code NULL = NULL} is never true) still
   * gets a debit fallback -- the counterparty's own debit, since a mandate-less obligation IS the
   * whole counterparty (spec review M1 edge case).
   */
  private List<ReviewQueueEntry> openContractEntries(boolean verbose) {
    var rows =
        db.select(
                CONTRACTS.ID,
                CONTRACTS.COUNTERPARTY_ID,
                CONTRACTS.MANDATE_ID,
                DISPLAY_NAME_EFFECTIVE,
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
                V_CONTRACT_EVIDENCE.TXN_COUNT,
                V_CONTRACT_EVIDENCE.LAST_SEEN,
                V_CONTRACT_EVIDENCE.DEBIT_LAST_365D,
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
            .from(CONTRACTS)
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .leftJoin(RECURRING)
            .on(RECURRING.CONTRACT_ID.eq(CONTRACTS.ID))
            .leftJoin(V_CONTRACT_EVIDENCE)
            .on(
                V_CONTRACT_EVIDENCE
                    .COUNTERPARTY_ID
                    .eq(CONTRACTS.COUNTERPARTY_ID)
                    .and(V_CONTRACT_EVIDENCE.MANDATE_ID.eq(CONTRACTS.MANDATE_ID)))
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .where(CONTRACTS.STATUS.eq("open"))
            .and(COUNTERPARTIES.MERGED_INTO.isNull())
            .fetch();

    List<ReviewQueueEntry> entries = new ArrayList<>();
    for (Record row : rows) {
      long counterpartyId = row.get(CONTRACTS.COUNTERPARTY_ID);
      RecurringView recurring = mapRecurring(row);
      BigDecimal contractDebit = row.get(V_CONTRACT_EVIDENCE.DEBIT_LAST_365D);
      BigDecimal counterpartyDebit = row.get(V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D);
      BigDecimal debitFallback = contractDebit != null ? contractDebit : counterpartyDebit;
      Long contractTxnCount = row.get(V_CONTRACT_EVIDENCE.TXN_COUNT);
      Integer txnCount =
          contractTxnCount != null
              ? contractTxnCount.intValue()
              : row.get(V_COUNTERPARTY_EVIDENCE.TXN_COUNT) == null
                  ? null
                  : row.get(V_COUNTERPARTY_EVIDENCE.TXN_COUNT).intValue();
      LocalDate lastSeen =
          row.get(V_CONTRACT_EVIDENCE.LAST_SEEN) != null
              ? row.get(V_CONTRACT_EVIDENCE.LAST_SEEN)
              : row.get(V_COUNTERPARTY_EVIDENCE.LAST_SEEN);
      CounterpartyEvidence evidence = mapEvidence(row, counterpartyId);
      entries.add(
          new ReviewQueueEntry(
              counterpartyId,
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(CONTRACTS.ID),
              verbose ? evidence : null,
              verbose ? recurring : null,
              AnnualCost.estimate(recurring, debitFallback),
              recurring == null ? null : recurring.cadence(),
              txnCount,
              lastSeen));
    }
    return entries;
  }

  /**
   * The legacy path (pre-TP1 behavior, preserved): an OPEN counterparty with NO {@code contracts}
   * row at all -- it was never contract-rooted (e.g. an ELV obligation that never carried a
   * {@code mandate_id}), so the whole counterparty remains the decision unit.
   */
  private List<ReviewQueueEntry> openCounterpartiesWithoutContractsEntries(boolean verbose) {
    var rows =
        db.select(
                COUNTERPARTIES.ID,
                DISPLAY_NAME_EFFECTIVE,
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
            .and(COUNTERPARTIES.MERGED_INTO.isNull())
            .and(
                DSL.notExists(
                    DSL.selectOne()
                        .from(CONTRACTS)
                        .where(CONTRACTS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))))
            // The evidence view is a LEFT JOIN: a bare `= 'DBIT'` would behave like an inner
            // join and silently drop an open counterparty with no evidence row yet. The IS
            // NULL branch keeps unknown-direction counterparties in the queue -- nothing
            // skips human review.
            .and(
                V_COUNTERPARTY_EVIDENCE
                    .DIRECTION
                    .eq("DBIT")
                    .or(V_COUNTERPARTY_EVIDENCE.DIRECTION.isNull()))
            .fetch();

    List<ReviewQueueEntry> entries = new ArrayList<>();
    for (Record row : rows) {
      long id = row.get(COUNTERPARTIES.ID);
      CounterpartyEvidence evidence = mapEvidence(row, id);
      RecurringView recurring = mapRecurring(row);
      String cadence = recurring == null ? null : recurring.cadence();
      Integer txnCount = evidence == null ? null : evidence.txnCount();
      LocalDate lastSeen = evidence == null ? null : evidence.lastSeen();
      entries.add(
          new ReviewQueueEntry(
              id,
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              null,
              verbose ? evidence : null,
              verbose ? recurring : null,
              AnnualCost.estimate(recurring, evidence),
              cadence,
              txnCount,
              lastSeen));
    }
    return entries;
  }

  public List<UnmatchedRecurringEntry> listUnmatchedRecurring(
      UnmatchedRecurringSort sort,
      Integer limit) {
    List<UnmatchedRecurringEntry> entries = new ArrayList<>();
    entries.addAll(unlinkedMandateContractEntries());
    entries.addAll(mandatelessRecurringEntries());

    if (sort == UnmatchedRecurringSort.annual_cost_desc) {
      entries.sort(Comparator.comparing(UnmatchedRecurringEntry::annualCostEstimate).reversed());
    }
    return limit != null && limit > 0 && entries.size() > limit
        ? entries.subList(0, limit)
        : entries;
  }

  /**
   * Branch (1) of {@link #listUnmatchedRecurring}: a {@code contracts} row with no {@code
   * hivemem_cell_id} yet, inner-joined to its {@code recurring} series (a contract without a
   * measured series yet has nothing to surface here).
   */
  private List<UnmatchedRecurringEntry> unlinkedMandateContractEntries() {
    var rows =
        db.select(
                CONTRACTS.ID,
                CONTRACTS.COUNTERPARTY_ID,
                DISPLAY_NAME_EFFECTIVE,
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
                RECURRING.CONFIDENCE,
                V_CONTRACT_EVIDENCE.DEBIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D)
            .from(CONTRACTS)
            .join(RECURRING)
            .on(RECURRING.CONTRACT_ID.eq(CONTRACTS.ID))
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .leftJoin(V_CONTRACT_EVIDENCE)
            .on(
                V_CONTRACT_EVIDENCE
                    .COUNTERPARTY_ID
                    .eq(CONTRACTS.COUNTERPARTY_ID)
                    .and(V_CONTRACT_EVIDENCE.MANDATE_ID.eq(CONTRACTS.MANDATE_ID)))
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .where(CONTRACTS.HIVEMEM_CELL_ID.isNull())
            // A dismissed contract has been explicitly rejected and needs no HiveMem link, so it
            // must not surface as "unmatched recurring" (#29 retroactivity; prevents the stale
            // lumped PayPal series double-count).
            .and(CONTRACTS.STATUS.ne("dismissed"))
            .and(CONTRACTS.STATUS.ne("ended"))
            .and(COUNTERPARTIES.MERGED_INTO.isNull())
            .fetch();

    List<UnmatchedRecurringEntry> entries = new ArrayList<>();
    for (Record row : rows) {
      RecurringView recurring = mapRecurring(row);
      BigDecimal contractDebit = row.get(V_CONTRACT_EVIDENCE.DEBIT_LAST_365D);
      BigDecimal counterpartyDebit = row.get(V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D);
      BigDecimal debitFallback = contractDebit != null ? contractDebit : counterpartyDebit;
      entries.add(
          new UnmatchedRecurringEntry(
              row.get(CONTRACTS.COUNTERPARTY_ID),
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(COUNTERPARTIES.IDENTITY_VALUE),
              row.get(CONTRACTS.ID),
              recurring,
              AnnualCost.estimate(recurring, debitFallback)));
    }
    return entries;
  }

  /**
   * Branch (2) of {@link #listUnmatchedRecurring}: a {@code recurring} row with no {@code
   * contract_id} -- a series that never carried a {@code mandate_id} (e.g. an ELV debit), so no
   * {@code contracts} row was ever derived for it.
   */
  private List<UnmatchedRecurringEntry> mandatelessRecurringEntries() {
    var rows =
        db.select(
                COUNTERPARTIES.ID,
                DISPLAY_NAME_EFFECTIVE,
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
                RECURRING.CONFIDENCE,
                V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D)
            .from(RECURRING)
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(RECURRING.COUNTERPARTY_ID))
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(RECURRING.COUNTERPARTY_ID))
            .where(RECURRING.CONTRACT_ID.isNull())
            .and(COUNTERPARTIES.MERGED_INTO.isNull())
            .fetch();

    List<UnmatchedRecurringEntry> entries = new ArrayList<>();
    for (Record row : rows) {
      RecurringView recurring = mapRecurring(row);
      BigDecimal debitFallback = row.get(V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D);
      entries.add(
          new UnmatchedRecurringEntry(
              row.get(COUNTERPARTIES.ID),
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(COUNTERPARTIES.IDENTITY_VALUE),
              null,
              recurring,
              AnnualCost.estimate(recurring, debitFallback)));
    }
    return entries;
  }

  public List<TransactionView> counterpartyTransactions(
      long counterpartyId,
      Integer period,
      LocalDate dateFrom,
      LocalDate dateTo) {
    Result<Record> rows =
        db.fetch(
            COUNTERPARTY_TRANSACTIONS_SQL,
            counterpartyId,
            dateFrom,
            dateTo,
            dateFrom,
            dateTo,
            dateFrom,
            dateTo,
            period,
            period);
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
              row.get("creditor_id", String.class),
              row.get("content_hash", String.class),
              row.get("occurrence_index", Integer.class)));
    }
    return transactions;
  }

  public List<TaxonomyDimension> taxonomy() {
    var rows =
        db.select(
                COUNTERPARTY_TAGS.DIMENSION,
                COUNTERPARTY_TAGS.VALUE,
                DSL.count().as("value_count"))
            .from(COUNTERPARTY_TAGS)
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(COUNTERPARTY_TAGS.COUNTERPARTY_ID))
            .where(COUNTERPARTIES.MERGED_INTO.isNull())
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

  public ObligationsRegister obligationsRegister() {
    var rows =
        db.select(
                CONTRACTS.ID,
                CONTRACTS.COUNTERPARTY_ID,
                CONTRACTS.MANDATE_ID,
                CONTRACTS.HIVEMEM_CELL_ID,
                DISPLAY_NAME_EFFECTIVE,
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
                V_CONTRACT_EVIDENCE.DEBIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D)
            .from(CONTRACTS)
            .join(COUNTERPARTIES)
            .on(COUNTERPARTIES.ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .leftJoin(RECURRING)
            .on(RECURRING.CONTRACT_ID.eq(CONTRACTS.ID))
            .leftJoin(V_CONTRACT_EVIDENCE)
            .on(
                V_CONTRACT_EVIDENCE
                    .COUNTERPARTY_ID
                    .eq(CONTRACTS.COUNTERPARTY_ID)
                    .and(V_CONTRACT_EVIDENCE.MANDATE_ID.eq(CONTRACTS.MANDATE_ID)))
            // A mandate-less contract's MANDATE_ID is NULL, so the join above never matches it
            // (NULL = NULL is not TRUE in SQL) -- fall back to the counterparty's own evidence,
            // since a mandate-less obligation IS the whole counterparty (spec review M1 edge
            // case).
            .leftJoin(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(CONTRACTS.COUNTERPARTY_ID))
            .where(CONTRACTS.STATUS.eq("confirmed"))
            .and(COUNTERPARTIES.MERGED_INTO.isNull())
            .and(
                DSL.notExists(
                    DSL.selectOne()
                        .from(COUNTERPARTY_TAGS)
                        .where(
                            COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(CONTRACTS.COUNTERPARTY_ID))
                        .and(COUNTERPARTY_TAGS.DIMENSION.eq("nature"))
                        .and(COUNTERPARTY_TAGS.VALUE.eq("zahlungsdienst"))
                        .and(COUNTERPARTY_TAGS.SOURCE.eq("confirmed"))))
            .fetch();

    Map<Long, List<CounterpartyTagView>> tagsByCounterparty = fetchTagsByCounterparty();

    List<ObligationRow> obligationRows = new ArrayList<>();
    for (Record row : rows) {
      long counterpartyId = row.get(CONTRACTS.COUNTERPARTY_ID);
      RecurringView recurring = mapRecurring(row);
      BigDecimal contractDebit = row.get(V_CONTRACT_EVIDENCE.DEBIT_LAST_365D);
      BigDecimal counterpartyDebit = row.get(V_COUNTERPARTY_EVIDENCE.DEBIT_LAST_365D);
      BigDecimal debitFallback = contractDebit != null ? contractDebit : counterpartyDebit;
      obligationRows.add(
          new ObligationRow(
              counterpartyId,
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              row.get(CONTRACTS.ID),
              row.get(CONTRACTS.MANDATE_ID),
              recurring == null ? null : recurring.cadence(),
              AnnualCost.estimate(recurring, debitFallback),
              tagsByCounterparty.getOrDefault(counterpartyId, List.of()),
              true,
              row.get(CONTRACTS.HIVEMEM_CELL_ID)));
    }

    obligationRows.sort(Comparator.comparing(ObligationRow::annualCost).reversed());

    BigDecimal total =
        obligationRows.stream()
            .map(ObligationRow::annualCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new ObligationsRegister(obligationRows, total);
  }

  public List<IncomeRow> listIncome() {
    var rows =
        db.select(
                COUNTERPARTIES.ID,
                DISPLAY_NAME_EFFECTIVE,
                COUNTERPARTIES.IDENTITY_TYPE,
                V_COUNTERPARTY_EVIDENCE.TXN_COUNT,
                V_COUNTERPARTY_EVIDENCE.CREDIT_LAST_365D,
                V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL,
                V_COUNTERPARTY_EVIDENCE.FIRST_SEEN,
                V_COUNTERPARTY_EVIDENCE.LAST_SEEN)
            .from(COUNTERPARTIES)
            .join(V_COUNTERPARTY_EVIDENCE)
            .on(V_COUNTERPARTY_EVIDENCE.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID))
            .where(V_COUNTERPARTY_EVIDENCE.DIRECTION.eq("CRDT"))
            .and(COUNTERPARTIES.MERGED_INTO.isNull())
            .orderBy(V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL.desc())
            .fetch();

    List<IncomeRow> income = new ArrayList<>();
    for (Record row : rows) {
      Long txnCount = row.get(V_COUNTERPARTY_EVIDENCE.TXN_COUNT);
      income.add(
          new IncomeRow(
              row.get(COUNTERPARTIES.ID),
              row.get(DISPLAY_NAME_EFFECTIVE),
              row.get(COUNTERPARTIES.IDENTITY_TYPE),
              txnCount == null ? 0 : txnCount,
              row.get(V_COUNTERPARTY_EVIDENCE.CREDIT_LAST_365D),
              row.get(V_COUNTERPARTY_EVIDENCE.CREDIT_TOTAL),
              row.get(V_COUNTERPARTY_EVIDENCE.FIRST_SEEN),
              row.get(V_COUNTERPARTY_EVIDENCE.LAST_SEEN)));
    }
    return income;
  }

  public SqlQueryResult sqlQuery(String sql) {
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

  private Map<Long, List<CounterpartyAliasView>> fetchAliasesByCounterparty() {
    Map<Long, List<CounterpartyAliasView>> byCounterparty = new LinkedHashMap<>();
    db.selectFrom(COUNTERPARTY_ALIAS)
        .fetch()
        .forEach(
            aliasRow ->
                byCounterparty
                    .computeIfAbsent(
                        aliasRow.get(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID),
                        key -> new ArrayList<>())
                    .add(
                        new CounterpartyAliasView(
                            aliasRow.get(COUNTERPARTY_ALIAS.IDENTITY_TYPE),
                            aliasRow.get(COUNTERPARTY_ALIAS.IDENTITY_VALUE))));
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
