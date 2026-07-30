package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static org.jooq.impl.DSL.array;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.table;

import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import de.visterion.aletheia.substrate.ProductPositionParser;
import de.visterion.aletheia.substrate.ProductSplitResolver;
import de.visterion.aletheia.substrate.SubstrateLock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authoring surface of {@code product_rules}: the five MCP tools that create, preview, edit,
 * pause and delete a rule (spec §5 lifecycle, §7 tools). The handlers stay thin, exactly as the
 * {@code tag_rules} handlers do.
 */
@Component
public class ProductRuleService {

  private static final Logger log = LoggerFactory.getLogger(ProductRuleService.class);

  /**
   * The roots a rule would act on: the resolver's candidate set <em>minus</em> its two skips.
   * Counting roots the resolver will refuse to touch would overstate every dry run on a mandate a
   * human has already split or re-attributed.
   *
   * <p>This is why the dry run reports {@code candidateRoots} while {@code list_product_rules}
   * reports {@code rootsVisited}: the resolver counts a skipped root as visited (it visits it and
   * decides to leave it alone), so on a creditor with human-decided bookings {@code rootsVisited >
   * candidateRoots} -- by exactly the number of skips. Two denominators, deliberately, and named
   * apart so nobody reads the difference as drift.
   */
  private static final String CANDIDATE_ROOTS =
      """
      SELECT t.amount, t.remittance_info
      FROM transactions t
      WHERE t.split_parent_content_hash IS NULL
        AND t.creditor_id = ?
        AND t.attributed_name IS NULL
        AND NOT EXISTS (SELECT 1 FROM transactions c
                        WHERE c.split_parent_content_hash = t.content_hash
                          AND c.split_parent_occurrence_index = t.occurrence_index
                          AND c.product IS NULL)
      """;

  /**
   * This creditor's product split children, addressed through their parent rather than through
   * their own {@code creditor_id}: the parent is what the rule was applied to, and a future writer
   * that stops inheriting the creditor id must not silently orphan them here.
   */
  private static final String PRODUCT_CHILDREN_PREDICATE =
      """
      product IS NOT NULL
        AND split_parent_content_hash IS NOT NULL
        AND EXISTS (SELECT 1 FROM transactions p
                    WHERE p.content_hash = transactions.split_parent_content_hash
                      AND p.occurrence_index = transactions.split_parent_occurrence_index
                      AND p.creditor_id = ?)
      """;

  private static final String STAMPED_ROOTS_PREDICATE =
      "split_parent_content_hash IS NULL AND product IS NOT NULL AND creditor_id = ?";

  /**
   * The creditor's counterparty, alias-aware. A SEPA mandate reference is unique only <em>per
   * creditor</em>, so a generic one ({@code 1}, {@code RECHNUNG}) collides across creditors and a
   * mandate-only scope would reach into a second creditor's contracts.
   *
   * <p>The alias hop is the frozen {@code COALESCE(al.canonical_counterparty_id, own.id)} idiom of
   * every other identity site ({@code ReadTools:228}, {@code ContractResolver:70}, {@code
   * CounterpartyResolver:119}, {@code TagRuleResolver:99}) -- contracts hang off the
   * <em>effective</em> counterparty, so resolving the creditor without the hop would silently miss
   * every contract of a folded creditor.
   */
  private static final String EFFECTIVE_CREDITOR_COUNTERPARTY =
      """
      SELECT COALESCE(al.canonical_counterparty_id, own.id) AS effective_cp
      FROM (SELECT 'creditor_id' AS identity_type, CAST(? AS text) AS identity_value) i
      LEFT JOIN counterparty_alias al ON al.identity_type = i.identity_type AND al.identity_value = i.identity_value
      LEFT JOIN counterparties own ON own.identity_type = i.identity_type AND own.identity_value = i.identity_value
      """;

  /**
   * Product contracts of this creditor's counterparty, on the mandates it books on. Contracts carry
   * no creditor id of their own, so the mandate is one half of the join -- and the counterparty is
   * the other half, because the mandate alone is not creditor-unique (see {@link
   * #EFFECTIVE_CREDITOR_COUNTERPARTY}). {@code CounterpartyMergeService.migrateOneContract} already
   * matches {@code (mandate_id, product)} within one counterparty for the same reason.
   */
  private static final String PRODUCT_CONTRACTS =
      """
      SELECT c.id, c.counterparty_id, c.mandate_id, c.product, c.status, c.source
      FROM contracts c
      WHERE c.product IS NOT NULL
        AND c.counterparty_id = ?
        AND EXISTS (SELECT 1 FROM transactions t
                    WHERE t.creditor_id = ? AND t.mandate_id = c.mandate_id)
      ORDER BY c.mandate_id, c.product
      """;

  /**
   * The mandate-level rows the rollout ended (spec §8 step 5). Deleting the rule sends every
   * booking back into this group, and {@code UPSERT_CONTRACTS}' {@code ON CONFLICT DO NOTHING}
   * never reopens an existing row -- so without this the creditor disappears from the register (it
   * selects {@code confirmed}), the review queue (it selects {@code open}) and {@code
   * list_unmatched_recurring} (it excludes {@code ended}) in one step, with the rule's counter
   * surface gone too.
   *
   * <p>Scoped to the creditor's own counterparty as well as to the mandate, and that scope is the
   * reachable half of the mandate-uniqueness problem: without it, deleting a rule would flip
   * <em>any</em> ended mandate contract of <em>any</em> counterparty whose mandate string happens
   * to collide back to {@code open} -- reviving a contract a human deliberately ended, with an
   * audit row that blames this tool.
   */
  private static final String ENDED_MANDATE_CONTRACTS =
      """
      SELECT c.id, c.counterparty_id, c.mandate_id
      FROM contracts c
      WHERE c.product IS NULL
        AND c.status = 'ended'
        AND c.mandate_id IS NOT NULL
        AND c.counterparty_id = ?
        AND EXISTS (SELECT 1 FROM transactions t
                    WHERE t.creditor_id = ? AND t.mandate_id = c.mandate_id)
      ORDER BY c.id
      """;

  private final DSLContext db;
  private final SubstrateLock substrateLock;
  private final CounterpartyResolver counterpartyResolver;
  private final ProductSplitResolver productSplitResolver;
  private final ContractResolver contractResolver;
  private final TransactionTemplate tx;

  // Pure, DB-free and Spring-free by design (see ProductPositionParser); never a bean.
  private final ProductPositionParser parser = new ProductPositionParser();

  public ProductRuleService(
      DSLContext db,
      SubstrateLock substrateLock,
      CounterpartyResolver counterpartyResolver,
      ProductSplitResolver productSplitResolver,
      ContractResolver contractResolver,
      PlatformTransactionManager txManager) {
    this.db = db;
    this.substrateLock = substrateLock;
    this.counterpartyResolver = counterpartyResolver;
    this.productSplitResolver = productSplitResolver;
    this.contractResolver = contractResolver;
    this.tx = new TransactionTemplate(txManager);
  }

  // -----------------------------------------------------------------------------------------
  // create
  // -----------------------------------------------------------------------------------------

  public ProductRuleAck createProductRule(
      String creditorId, String positionPattern, String notes, Boolean dryRun) {
    String creditor = requireText(creditorId, "creditorId");
    validatePattern(positionPattern);
    requireKnownCreditor(creditor);
    boolean preview = Boolean.TRUE.equals(dryRun);

    substrateLock.lock();
    try {
      // Before the preview, not after it: a dry run that happily previews a create the very next
      // call would reject is a preview of something that cannot happen.
      Long existing = ruleIdOfCreditor(creditor);
      if (existing != null) {
        throw new IllegalArgumentException(
            "a product rule for " + creditor + " already exists (id " + existing
                + "); edit it with update_product_rule instead of creating a second one");
      }
      ProductRuleBlastRadius radius = blastRadius(creditor, positionPattern);
      if (preview) {
        return new ProductRuleAck(null, "dry run, nothing written: " + describe(radius), true,
            radius, null);
      }
      long ruleId =
          db.fetchOne(
                  "INSERT INTO product_rules (creditor_id, position_pattern, notes)"
                      + " VALUES (?, ?, ?) RETURNING id",
                  creditor,
                  positionPattern,
                  notes)
              .get("id", Long.class);
      settle();
      return new ProductRuleAck(
          ruleId, "created product rule " + ruleId + " for " + creditor + "; " + describe(radius),
          false, radius, null);
    } finally {
      substrateLock.unlock();
    }
  }

  // -----------------------------------------------------------------------------------------
  // update
  // -----------------------------------------------------------------------------------------

  /**
   * Edits a rule in place. This exists because {@code uq_product_rules_creditor} allows one rule per
   * creditor: without it the only production path would be delete + create, which runs the full
   * revert (auto contracts included) and never exercises the graceful refresh the resolver
   * implements.
   *
   * <p>An omitted field is left unchanged; passing an empty {@code notes} clears the note.
   */
  public ProductRuleAck updateProductRule(
      Long ruleId, String positionPattern, String notes, Boolean dryRun) {
    Record rule = requireRule(ruleId);
    String creditor = rule.get("creditor_id", String.class);
    String effectivePattern =
        positionPattern == null || positionPattern.isBlank()
            ? rule.get("position_pattern", String.class)
            : positionPattern;
    validatePattern(effectivePattern);
    boolean preview = Boolean.TRUE.equals(dryRun);

    substrateLock.lock();
    try {
      ProductRuleBlastRadius radius = blastRadius(creditor, effectivePattern);
      if (preview) {
        return new ProductRuleAck(
            ruleId, "dry run, nothing written: " + describe(radius), true, radius, null);
      }
      db.execute(
          "UPDATE product_rules SET position_pattern = ?, notes = coalesce(?, notes) WHERE id = ?",
          effectivePattern,
          notes,
          ruleId);
      settle();
      return new ProductRuleAck(
          ruleId, "updated product rule " + ruleId + "; " + describe(radius), false, radius, null);
    } finally {
      substrateLock.unlock();
    }
  }

  // -----------------------------------------------------------------------------------------
  // list
  // -----------------------------------------------------------------------------------------

  public List<ProductRuleView> listProductRules() {
    return db
        .fetch(
            "SELECT id, creditor_id, position_pattern, enabled, notes, created_at,"
                + " last_resolved_at, roots_visited, roots_split, roots_stamped, roots_mismatched"
                + " FROM product_rules ORDER BY id")
        .map(
            r ->
                new ProductRuleView(
                    r.get("id", Long.class),
                    r.get("creditor_id", String.class),
                    r.get("position_pattern", String.class),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                    r.get("notes", String.class),
                    text(r.get("created_at")),
                    text(r.get("last_resolved_at")),
                    r.get("roots_visited", Integer.class),
                    r.get("roots_split", Integer.class),
                    r.get("roots_stamped", Integer.class),
                    r.get("roots_mismatched", Integer.class)));
  }

  // -----------------------------------------------------------------------------------------
  // enable / disable
  // -----------------------------------------------------------------------------------------

  /** Disabling is a pause, not a revert: existing children and stamps stay in place (spec §5). */
  public ProductRuleAck setProductRuleEnabled(Long ruleId, Boolean enabled) {
    requireRule(ruleId);
    boolean on = Boolean.TRUE.equals(enabled);

    substrateLock.lock();
    try {
      db.execute("UPDATE product_rules SET enabled = ? WHERE id = ?", on, ruleId);
      if (on) {
        settle();
      }
      return new ProductRuleAck(
          ruleId,
          on
              ? "enabled product rule " + ruleId
              : "paused product rule " + ruleId
                  + "; existing product children and stamps are left in place",
          false,
          null,
          null);
    } finally {
      substrateLock.unlock();
    }
  }

  // -----------------------------------------------------------------------------------------
  // delete
  // -----------------------------------------------------------------------------------------

  /**
   * Full revert (spec §5, "rule deleted"). The resolver cannot do this itself: it only visits
   * creditors with an <em>enabled</em> rule, and {@code ContractResolver} contains no DELETE at all,
   * so a deleted rule would otherwise leave orphaned {@code open} product contracts with frozen
   * amounts in the review queue forever.
   */
  public ProductRuleAck deleteProductRule(Long ruleId, Boolean dryRun) {
    Record rule = requireRule(ruleId);
    String creditor = rule.get("creditor_id", String.class);
    boolean preview = Boolean.TRUE.equals(dryRun);

    substrateLock.lock();
    try {
      if (preview) {
        ProductRuleRevert plan = revertPlan(creditor);
        return new ProductRuleAck(
            ruleId, "dry run, nothing written: " + describe(plan), true, null, plan);
      }
      ProductRuleRevert done = tx.execute(status -> revert(ruleId, creditor));
      // After the revert commits: the creditor has no rule any more, so the product pass is a
      // no-op for it, but ContractResolver must re-derive the mandate-level series from the
      // bookings that just returned to that group.
      settle();
      return new ProductRuleAck(
          ruleId, "deleted product rule " + ruleId + "; " + describe(done), false, null, done);
    } finally {
      substrateLock.unlock();
    }
  }

  private ProductRuleRevert revertPlan(String creditor) {
    Long counterpartyId = effectiveCounterpartyOf(creditor);
    List<Record> contracts = db.fetch(PRODUCT_CONTRACTS, counterpartyId, creditor);
    List<Long> autoIds = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    partitionContracts(contracts, autoIds, kept);
    return new ProductRuleRevert(
        db.fetchCount(
            table("transactions"),
            condition(PRODUCT_CHILDREN_PREDICATE, creditor)),
        db.fetchCount(
            table("transactions"),
            condition(STAMPED_ROOTS_PREDICATE, creditor)),
        autoIds.size(),
        recurringCount(autoIds),
        db.fetch(ENDED_MANDATE_CONTRACTS, counterpartyId, creditor).size(),
        List.copyOf(kept));
  }

  private ProductRuleRevert revert(long ruleId, String creditor) {
    Long counterpartyId = effectiveCounterpartyOf(creditor);
    List<Record> contracts = db.fetch(PRODUCT_CONTRACTS, counterpartyId, creditor);
    List<Long> autoIds = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    partitionContracts(contracts, autoIds, kept);

    int children =
        db.execute(
            "DELETE FROM transactions WHERE " + PRODUCT_CHILDREN_PREDICATE.replace("\n", " "),
            creditor);
    int stamps =
        db.execute(
            "UPDATE transactions SET product = NULL, product_policy_no = NULL WHERE "
                + STAMPED_ROOTS_PREDICATE,
            creditor);

    int recurring = 0;
    if (!autoIds.isEmpty()) {
      recurring =
          db.execute(
              "DELETE FROM recurring WHERE contract_id = ANY (?)",
              array(autoIds.toArray(Long[]::new)));
      db.execute(
          "DELETE FROM contracts WHERE id = ANY (?)",
          array(autoIds.toArray(Long[]::new)));
    }

    int reopened = 0;
    for (Record ended : db.fetch(ENDED_MANDATE_CONTRACTS, counterpartyId, creditor)) {
      long contractId = ended.get("id", Long.class);
      db.execute(
          "UPDATE contracts SET status = 'open', end_date = NULL WHERE id = ?", contractId);
      insertReopenHistory(ended.get("counterparty_id", Long.class), contractId);
      reopened++;
    }

    db.execute("DELETE FROM product_rules WHERE id = ?", ruleId);

    if (!kept.isEmpty()) {
      log.warn(
          "delete_product_rule {}: kept {} human-decided product contract(s) that no booking"
              + " carries any more: {}",
          ruleId,
          kept.size(),
          kept);
    }
    return new ProductRuleRevert(
        children, stamps, autoIds.size(), recurring, reopened, List.copyOf(kept));
  }

  /**
   * Audits the reopen. The row it flips was confirmed by a human before it was ended, so the status
   * change has to be traceable: {@code actor} is the calling principal ({@link RequestActor}, the
   * convention every other history write follows -- this service runs on the request thread like
   * any other tool), and the tool name goes into {@code source}, where {@code end_contract} puts
   * its reason.
   */
  private void insertReopenHistory(long counterpartyId, long contractId) {
    db.execute(
        "INSERT INTO counterparty_history (counterparty_id, field, old_value, new_value, source,"
            + " actor) VALUES (?, ?, 'ended', 'open', 'reopened by delete_product_rule', ?)",
        counterpartyId,
        "contract:" + contractId,
        RequestActor.current());
  }

  /**
   * Resolves the creditor to its effective counterparty; see {@link
   * #EFFECTIVE_CREDITOR_COUNTERPARTY}. A {@code null} result (no counterparty for this creditor id
   * yet) is deliberately passed on to the scoped queries, where {@code counterparty_id = NULL}
   * matches nothing: fail closed, never fall back to a mandate-only scope.
   */
  private Long effectiveCounterpartyOf(String creditorId) {
    Record row = db.fetchOne(EFFECTIVE_CREDITOR_COUNTERPARTY, creditorId);
    return row == null ? null : row.get("effective_cp", Long.class);
  }

  /**
   * Settles the substrate in-call, under the already-held lock (spec §5, review Minor 4). Ingest
   * here is a manual file upload, so a rule that only took effect at the next trigger could sit
   * inert for weeks; {@code set_tag_rule_enabled}'s no-resolve behaviour is the wrong precedent for
   * that reason. The order matches the resolvers' {@code @Order}: identity, then products, then the
   * contract grain that consumes them. Tag rules are deliberately not run -- a product split writes
   * child rows on a counterparty that already exists, so no counterparty becomes newly taggable.
   */
  private void settle() {
    counterpartyResolver.resolve();
    productSplitResolver.resolve();
    contractResolver.resolve();
  }

  /**
   * Splits the creditor's product contracts into the strictly-auto ones (deletable) and the
   * human-decided ones (kept and reported). "Strictly auto" is {@code source='auto'} <b>and</b>
   * {@code status='open'}: a confirmed, dismissed or ended row all carry a human decision, and a
   * tool call never destroys one -- the same reasoning {@code CounterpartyMergeService} applies when
   * it drops only the auto obligation layer of a folded counterparty.
   */
  private static void partitionContracts(
      List<Record> contracts, List<Long> autoIds, List<String> kept) {
    for (Record c : contracts) {
      boolean strictlyAuto =
          "auto".equals(c.get("source", String.class)) && "open".equals(c.get("status", String.class));
      if (strictlyAuto) {
        autoIds.add(c.get("id", Long.class));
      } else {
        kept.add(
            "contract "
                + c.get("id", Long.class)
                + " (mandate "
                + c.get("mandate_id", String.class)
                + ", product "
                + c.get("product", String.class)
                + ", status "
                + c.get("status", String.class)
                + ")");
      }
    }
  }

  private int recurringCount(List<Long> contractIds) {
    if (contractIds.isEmpty()) {
      return 0;
    }
    return db.fetchOne(
            "SELECT count(*) AS c FROM recurring WHERE contract_id = ANY (?)",
            array(contractIds.toArray(Long[]::new)))
        .get("c", Integer.class)
        .intValue();
  }

  // -----------------------------------------------------------------------------------------
  // blast radius, validation, helpers
  // -----------------------------------------------------------------------------------------

  /**
   * Parses the creditor's whole candidate history without writing anything. The parse is the same
   * one the resolver performs, so the numbers a dry run reports are the numbers the settle produces.
   */
  private ProductRuleBlastRadius blastRadius(String creditorId, String positionPattern) {
    int candidates = 0;
    int matched = 0;
    int positions = 0;
    int mismatches = 0;
    for (Record root : db.fetch(CANDIDATE_ROOTS, creditorId)) {
      candidates++;
      ProductPositionParser.ParseResult parsed =
          parser.parse(
              positionPattern,
              root.get("remittance_info", String.class),
              root.get("amount", BigDecimal.class));
      if (parsed.sumMismatch()) {
        mismatches++;
      } else if (!parsed.positions().isEmpty()) {
        matched++;
        positions += parsed.positions().size();
      }
    }
    return new ProductRuleBlastRadius(candidates, matched, positions, mismatches);
  }

  /** Reuses the parser's own validation; a second copy of it would drift from what runs. */
  private void validatePattern(String positionPattern) {
    parser.parse(requireText(positionPattern, "positionPattern"), null, BigDecimal.ZERO);
  }

  private void requireKnownCreditor(String creditorId) {
    boolean known =
        db.fetchExists(
            db.selectOne()
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_TYPE.eq("creditor_id"))
                .and(COUNTERPARTIES.IDENTITY_VALUE.eq(creditorId)));
    if (!known) {
      throw new IllegalArgumentException(
          "no counterparty exists for creditor_id '"
              + creditorId
              + "'; check it against list_counterparties before authoring a rule");
    }
  }

  private Record requireRule(Long ruleId) {
    Record rule =
        ruleId == null
            ? null
            : db.fetchOne("SELECT id, creditor_id, position_pattern FROM product_rules WHERE id = ?",
                ruleId);
    if (rule == null) {
      throw new IllegalArgumentException("no such product rule: " + ruleId);
    }
    return rule;
  }

  private Long ruleIdOfCreditor(String creditorId) {
    Record row = db.fetchOne("SELECT id FROM product_rules WHERE creditor_id = ?", creditorId);
    return row == null ? null : row.get("id", Long.class);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static String describe(ProductRuleBlastRadius radius) {
    return radius.bookingsMatched()
        + " of "
        + radius.candidateRoots()
        + " candidate booking(s) match ("
        + radius.positionsParsed()
        + " position(s)), "
        + radius.sumMismatches()
        + " sum mismatch(es) left untouched";
  }

  private static String describe(ProductRuleRevert revert) {
    String base =
        revert.childrenRemoved()
            + " product child row(s) removed, "
            + revert.stampsCleared()
            + " stamp(s) cleared, "
            + revert.autoContractsDeleted()
            + " auto product contract(s) deleted, "
            + revert.mandateContractsReopened()
            + " ended mandate contract(s) reopened";
    return revert.keptConfirmedContracts().isEmpty()
        ? base
        : base
            + "; kept "
            + revert.keptConfirmedContracts().size()
            + " human-decided product contract(s) that no booking carries any more: "
            + String.join(", ", revert.keptConfirmedContracts());
  }
}
