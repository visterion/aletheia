package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.PRODUCT_RULES;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;

import de.visterion.aletheia.jooq.tables.records.TransactionsRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record5;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a booking that bundles several products under one SEPA mandate into one row per product
 * (spec §5), so {@code ContractResolver} can derive contracts at {@code (counterparty_id,
 * mandate_id, product)} instead of lumping unrelated obligations into one.
 *
 * <p>Deterministic and replayable: an LLM authors the {@code product_rules} row once, execution
 * happens here with no LLM in the ingest path -- the same discipline {@code tag_rules} follows.
 * Only creditors with an <em>enabled</em> rule are visited; every other mandate keeps today's
 * behaviour exactly.
 *
 * <p>Per raw root of such a creditor:
 *
 * <ul>
 *   <li>a root with {@code attributed_name} is skipped -- a human {@code reattribute_transaction}
 *       is a decision, and splitting would hide the parent from every evidence view and erase that
 *       identity without an error
 *   <li>a root with any child carrying {@code product IS NULL} is skipped, because that is a human
 *       {@code split_transaction} and the human outranks the rule. The skip also <b>clears a stale
 *       stamp</b> on the root: otherwise the pre-split full amount would keep feeding the product
 *       contract while the human children feed the NULL group
 *   <li>0 positions parsed: product children removed and the stamp cleared (a no-op for a booking
 *       that never parsed)
 *   <li>exactly 1 position summing to the booking amount: the root is <b>stamped</b>, and any
 *       product children are deleted (the N&rarr;1 rule-edit transition)
 *   <li>&ge;2 positions summing to the booking amount: one child per <b>folded</b> product is
 *       written, and the root's stamp is cleared (the 1&rarr;N transition). Note the branch is
 *       decided by the number of <em>parsed</em> positions, not folded ones: a booking carrying the
 *       same product twice is a split with a single folded child, not a stamp, so the amount that
 *       reaches the contract layer is the folded sum and not one of the two halves
 *   <li>sum mismatch: the booking is left untouched and counted. No tolerance, no epsilon -- a
 *       creditor that changes its format must surface as unmatched, never as a silently
 *       mis-attributed contract
 * </ul>
 *
 * <p><b>Normalization happens in the database, in one batched query per root.</b> Product identity
 * is the SQL normal form; Java cannot reproduce it (PostgreSQL's {@code \s} collapses a wider
 * class, {@code upper()} delegates to libc -- see {@link NameNormalization}). A Java-side normal
 * form would either violate V19's CHECK at ingest time or, worse, pass it while differing from the
 * SQL form and mint two contracts for one product. The batching matters too: one {@link
 * NameNormalization#evaluate} roundtrip per position costs hundreds of extra roundtrips per pass on
 * a real mandate's history, on every ingest, startup, reattribute and merge.
 *
 * <p>Idempotent. Desired and existing children are compared on {@code (product, amount,
 * product_policy_no, remittance_info)} at their index-derived hashes; identical means no write at
 * all, different means delete this root's children <em>that carry a product</em> and recreate.
 * Positions are ordered by normalized product name before writing, because the child key is {@link
 * SplitChildWriter#syntheticSplitHash} -- unstable ordering would mint new rows on every run.
 *
 * <p>{@code @Order(4)} places this after {@link CounterpartyResolver} ({@code @Order(3)}) and
 * before {@link ContractResolver} ({@code @Order(5)}), which consumes the product data this
 * produces.
 */
@Component
@Order(4)
public class ProductSplitResolver implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ProductSplitResolver.class);

  private static final String LOAD_ENABLED_RULES =
      "SELECT id, creditor_id, position_pattern, enabled, notes FROM product_rules"
          + " WHERE enabled ORDER BY id";

  /**
   * Normalizes a whole root's raw product names in one roundtrip, order-preserving.
   *
   * <p>{@code WITH ORDINALITY} rather than a bare {@code unnest}: the zip back onto the parsed
   * positions is positional, so relying on the (unspecified) natural order of a set-returning
   * function would be a silent mis-assignment of amounts to products.
   */
  private static final String NORMALIZE_BATCH =
      "SELECT "
          + NameNormalization.identitySql("u.v")
          + " AS n FROM unnest(cast(? as text[])) WITH ORDINALITY AS u(v, ord) ORDER BY u.ord";

  private final DSLContext db;
  private final TransactionTemplate tx;
  private final SubstrateLock substrateLock;
  private final SplitChildWriter childWriter;

  // Stateless and not a Spring bean (it is pure, DB-free and Spring-free by design, so that no
  // future edit can reach a DSLContext from inside it and start normalizing in Java).
  private final ProductPositionParser parser = new ProductPositionParser();

  public ProductSplitResolver(
      DSLContext db,
      PlatformTransactionManager txManager,
      SubstrateLock substrateLock,
      SplitChildWriter childWriter) {
    this.db = db;
    this.tx = new TransactionTemplate(txManager);
    this.substrateLock = substrateLock;
    this.childWriter = childWriter;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      resolve();
    } catch (RuntimeException e) {
      log.warn(
          "Startup product split resolution failed; will retry on next ingest/restart: {}",
          e.toString());
    }
  }

  /**
   * Applies every enabled rule.
   *
   * <p>Resilience is two-layered and both layers are real: a bad rule (an uncompilable pattern, a
   * pattern missing a required capture group) is logged and skipped so the remaining rules still
   * run, and <em>within</em> a rule a single failing root is logged at WARN with its identity,
   * counted and stepped over so the creditor's remaining history is still processed. The counter
   * write happens in a {@code finally}, so a failure can never leave {@code list_product_rules} and
   * the {@code wake_up} warning line showing the previous pass's numbers.
   */
  public void resolve() {
    substrateLock.lock();
    try {
      for (ProductRule rule : loadEnabledRules()) {
        try {
          resolveRule(rule);
        } catch (RuntimeException e) {
          log.warn("Skipping product rule {} ({}): {}", rule.id(), rule.creditorId(), e.toString());
        }
      }
    } finally {
      substrateLock.unlock();
    }
  }

  private List<ProductRule> loadEnabledRules() {
    return db.fetch(LOAD_ENABLED_RULES)
        .map(
            r ->
                new ProductRule(
                    r.get("id", Long.class),
                    r.get("creditor_id", String.class),
                    r.get("position_pattern", String.class),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                    r.get("notes", String.class)));
  }

  private void resolveRule(ProductRule rule) {
    // Fail fast on an unusable pattern, before touching a single row.
    parser.parse(rule.positionPattern(), null, BigDecimal.ZERO);

    Result<TransactionsRecord> roots =
        db.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.isNull())
            .and(TRANSACTIONS.CREDITOR_ID.eq(rule.creditorId()))
            .orderBy(TRANSACTIONS.CONTENT_HASH, TRANSACTIONS.OCCURRENCE_INDEX)
            .fetch();

    Counters counters = new Counters();
    try {
      for (Record root : roots) {
        counters.visited++;
        // Per-root isolation. One root that throws -- a CHECK violation, or a natural-key
        // collision with a concurrent writer -- must not abort the creditor's remaining history,
        // and must not cost the counter surface its update: the residue numbers matter most
        // exactly when something is going wrong.
        try {
          Outcome outcome = tx.execute(status -> resolveRoot(rule, root));
          counters.record(outcome);
        } catch (RuntimeException e) {
          counters.failed++;
          log.warn(
              "Product rule {} ({}): root {}/{} failed and was skipped: {}",
              rule.id(),
              rule.creditorId(),
              root.get(TRANSACTIONS.CONTENT_HASH),
              root.get(TRANSACTIONS.OCCURRENCE_INDEX),
              e.toString());
        }
      }
    } finally {
      writeCounters(rule, counters);
    }

    log.info(
        "Product rule {} ({}): visited {} root(s), split {}, stamped {}, mismatched {}, skipped {},"
            + " failed {}",
        rule.id(),
        rule.creditorId(),
        counters.visited,
        counters.split,
        counters.stamped,
        counters.mismatched,
        counters.skipped,
        counters.failed);
  }

  // -----------------------------------------------------------------------------------------
  // per root
  // -----------------------------------------------------------------------------------------

  private Outcome resolveRoot(ProductRule rule, Record root) {
    String hash = root.get(TRANSACTIONS.CONTENT_HASH);
    int occ = root.get(TRANSACTIONS.OCCURRENCE_INDEX);

    if (root.get(TRANSACTIONS.ATTRIBUTED_NAME) != null) {
      log.debug("Skipping attributed root {}/{}: a re-attribution is a human decision", hash, occ);
      return Outcome.SKIPPED;
    }
    if (hasHumanSplitChild(hash, occ)) {
      int cleared = clearStamp(hash, occ);
      log.debug(
          "Skipping human-split root {}/{} (cleared {} stale stamp(s))", hash, occ, cleared);
      return Outcome.SKIPPED;
    }

    BigDecimal amount = root.get(TRANSACTIONS.AMOUNT);
    ProductPositionParser.ParseResult parsed =
        parser.parse(rule.positionPattern(), root.get(TRANSACTIONS.REMITTANCE_INFO), amount);

    if (parsed.sumMismatch()) {
      log.info(
          "Product rule {}: positions of root {}/{} do not sum to {}; left untouched",
          rule.id(),
          hash,
          occ,
          amount);
      return Outcome.MISMATCHED;
    }
    if (parsed.positions().isEmpty()) {
      deleteProductChildren(hash, occ);
      clearStamp(hash, occ);
      return Outcome.NOTHING;
    }

    List<FoldedPosition> folded = fold(parsed.positions());
    if (folded.isEmpty()) {
      // Every product name normalized to empty -- an unusable rule, not a booking problem.
      log.warn(
          "Product rule {}: every product name of root {}/{} normalizes to empty; left untouched",
          rule.id(),
          hash,
          occ);
      return Outcome.NOTHING;
    }

    if (parsed.positions().size() == 1) {
      FoldedPosition only = folded.get(0);
      deleteProductChildren(hash, occ);
      stamp(hash, occ, only.product(), only.policyNo());
      return Outcome.STAMPED;
    }

    clearStamp(hash, occ);
    if (childrenAlreadyMatch(hash, occ, folded)) {
      return Outcome.SPLIT;
    }
    deleteProductChildren(hash, occ);
    for (int i = 0; i < folded.size(); i++) {
      FoldedPosition p = folded.get(i);
      childWriter.writeChild(
          db,
          root,
          SplitChildWriter.syntheticSplitHash(hash, i),
          new SplitChildWriter.ChildValues(
              p.amount(),
              p.remittanceInfo(),
              root.get(TRANSACTIONS.COUNTERPARTY_NAME),
              root.get(TRANSACTIONS.CREDITOR_ID),
              root.get(TRANSACTIONS.COUNTERPARTY_IBAN),
              root.get(TRANSACTIONS.MANDATE_ID),
              p.product(),
              p.policyNo()));
    }
    return Outcome.SPLIT;
  }

  private boolean hasHumanSplitChild(String parentHash, int occ) {
    return db.fetchExists(
        db.selectOne()
            .from(TRANSACTIONS)
            .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(parentHash))
            .and(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX.eq(occ))
            .and(TRANSACTIONS.PRODUCT.isNull()));
  }

  /** Never touches a {@code product IS NULL} child -- that one belongs to a human. */
  private int deleteProductChildren(String parentHash, int occ) {
    return db.deleteFrom(TRANSACTIONS)
        .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(parentHash))
        .and(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX.eq(occ))
        .and(TRANSACTIONS.PRODUCT.isNotNull())
        .execute();
  }

  private int clearStamp(String hash, int occ) {
    return db.update(TRANSACTIONS)
        .setNull(TRANSACTIONS.PRODUCT)
        .setNull(TRANSACTIONS.PRODUCT_POLICY_NO)
        .where(TRANSACTIONS.CONTENT_HASH.eq(hash))
        .and(TRANSACTIONS.OCCURRENCE_INDEX.eq(occ))
        .and(TRANSACTIONS.PRODUCT.isNotNull())
        .execute();
  }

  private void stamp(String hash, int occ, String product, String policyNo) {
    db.update(TRANSACTIONS)
        .set(TRANSACTIONS.PRODUCT, product)
        .set(TRANSACTIONS.PRODUCT_POLICY_NO, policyNo)
        .where(TRANSACTIONS.CONTENT_HASH.eq(hash))
        .and(TRANSACTIONS.OCCURRENCE_INDEX.eq(occ))
        .and(
            TRANSACTIONS
                .PRODUCT
                .isDistinctFrom(product)
                .or(TRANSACTIONS.PRODUCT_POLICY_NO.isDistinctFrom(policyNo)))
        .execute();
  }

  /**
   * Whether the existing children are already exactly the desired ones, at the very hashes the
   * writer would use. Comparing by hash rather than by set makes a reordering visible, which
   * matters because the hash is index-derived.
   */
  private boolean childrenAlreadyMatch(String parentHash, int occ, List<FoldedPosition> desired) {
    Result<Record5<String, String, BigDecimal, String, String>> existing =
        db.select(
                TRANSACTIONS.CONTENT_HASH,
                TRANSACTIONS.PRODUCT,
                TRANSACTIONS.AMOUNT,
                TRANSACTIONS.PRODUCT_POLICY_NO,
                TRANSACTIONS.REMITTANCE_INFO)
            .from(TRANSACTIONS)
            .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(parentHash))
            .and(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX.eq(occ))
            .and(TRANSACTIONS.PRODUCT.isNotNull())
            .fetch();
    if (existing.size() != desired.size()) {
      return false;
    }
    Map<String, Record5<String, String, BigDecimal, String, String>> byHash = new LinkedHashMap<>();
    for (var r : existing) {
      byHash.put(r.value1(), r);
    }

    for (int i = 0; i < desired.size(); i++) {
      FoldedPosition want = desired.get(i);
      var have = byHash.get(SplitChildWriter.syntheticSplitHash(parentHash, i));
      if (have == null
          || !want.product().equals(have.value2())
          || have.value3() == null
          || want.amount().compareTo(have.value3()) != 0
          || !Objects.equals(want.policyNo(), have.value4())
          || !Objects.equals(want.remittanceInfo(), have.value5())) {
        return false;
      }
    }
    return true;
  }

  // -----------------------------------------------------------------------------------------
  // normalization + folding
  // -----------------------------------------------------------------------------------------

  /**
   * Folds the parsed positions by normalized product name.
   *
   * <p>Amounts are summed, so the historical booking carrying the same product twice yields one
   * child instead of two colliding rows. The first <em>non-null</em> policy number wins; a
   * conflicting later one is dropped and logged rather than raised (it is part of no key). The
   * folded {@code remittance_info} is the concatenation of the matched substrings in fold order --
   * it participates in the idempotency key, so an unspecified value would re-delete and recreate
   * the children on every single pass.
   *
   * <p>The result is sorted by product name, because {@link SplitChildWriter#syntheticSplitHash} is
   * index-derived.
   */
  private List<FoldedPosition> fold(List<ProductPosition> positions) {
    List<String> normalized =
        normalizeAll(positions.stream().map(ProductPosition::rawProduct).toList());

    Map<String, Accumulator> byProduct = new LinkedHashMap<>();
    for (int i = 0; i < positions.size(); i++) {
      String product = normalized.get(i);
      if (product == null || product.isEmpty()) {
        continue;
      }
      ProductPosition p = positions.get(i);
      Accumulator acc = byProduct.computeIfAbsent(product, k -> new Accumulator());
      acc.amount = acc.amount.add(p.amount());
      if (acc.policyNo == null) {
        acc.policyNo = p.policyNo();
      } else if (p.policyNo() != null && !acc.policyNo.equals(p.policyNo())) {
        log.info(
            "Product {}: dropping conflicting policy number '{}' (keeping '{}')",
            product,
            p.policyNo(),
            acc.policyNo);
      }
      if (!acc.remittance.isEmpty()) {
        acc.remittance.append(' ');
      }
      acc.remittance.append(p.matchedText());
    }

    List<FoldedPosition> folded = new ArrayList<>(byProduct.size());
    for (var e : byProduct.entrySet()) {
      folded.add(
          new FoldedPosition(
              e.getKey(),
              e.getValue().policyNo,
              e.getValue().amount,
              e.getValue().remittance.toString()));
    }
    folded.sort(Comparator.comparing(FoldedPosition::product));
    return folded;
  }

  /** One batched roundtrip; see {@link #NORMALIZE_BATCH}. */
  private List<String> normalizeAll(List<String> raw) {
    if (raw.isEmpty()) {
      return List.of();
    }
    return db.fetch(NORMALIZE_BATCH, (Object) raw.toArray(String[]::new))
        .map(r -> r.get("n", String.class));
  }

  private static final class Accumulator {
    private BigDecimal amount = BigDecimal.ZERO;
    private String policyNo;
    private final StringBuilder remittance = new StringBuilder();
  }

  /** One product's share of a booking, after normalization and folding. */
  private record FoldedPosition(
      String product, String policyNo, BigDecimal amount, String remittanceInfo) {}

  // -----------------------------------------------------------------------------------------
  // counters
  // -----------------------------------------------------------------------------------------

  private enum Outcome {
    SPLIT,
    STAMPED,
    MISMATCHED,
    SKIPPED,
    NOTHING
  }

  private static final class Counters {
    private int visited;
    private int split;
    private int stamped;
    private int mismatched;
    private int skipped;

    /**
     * Roots whose unit threw. In-memory and log-only: {@code product_rules} has no column for it
     * (V19, spec §3), and adding one is a migration, not part of this fix. {@code roots_visited}
     * still counts them, so a persistent failure shows up as visited &gt; split + stamped +
     * mismatched on the residue surface.
     */
    private int failed;

    void record(Outcome outcome) {
      switch (outcome) {
        case SPLIT -> split++;
        case STAMPED -> stamped++;
        case MISMATCHED -> mismatched++;
        case SKIPPED -> skipped++;
        case NOTHING -> {}
      }
    }
  }

  /**
   * Writes the state of the pass that just finished, not a running total.
   *
   * <p>A pass always visits the creditor's whole history, so absolute values answer the question
   * these counters exist for -- "how much of this creditor's history does the rule still explain?"
   * -- while running totals would only grow with the number of ingests. They are the residue
   * surface: {@code list_product_rules} and the {@code wake_up} live-state block read them, so a
   * creditor that silently reformats its remittance shows up as a rising {@code roots_mismatched}
   * instead of vanishing into container logs.
   */
  private void writeCounters(ProductRule rule, Counters counters) {
    db.update(PRODUCT_RULES)
        .set(PRODUCT_RULES.LAST_RESOLVED_AT, OffsetDateTime.now())
        .set(PRODUCT_RULES.ROOTS_VISITED, counters.visited)
        .set(PRODUCT_RULES.ROOTS_SPLIT, counters.split)
        .set(PRODUCT_RULES.ROOTS_STAMPED, counters.stamped)
        .set(PRODUCT_RULES.ROOTS_MISMATCHED, counters.mismatched)
        .where(PRODUCT_RULES.ID.eq(rule.id()))
        .execute();
  }
}
