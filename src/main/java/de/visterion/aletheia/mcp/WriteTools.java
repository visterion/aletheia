package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import de.visterion.aletheia.substrate.SubstrateLock;
import de.visterion.aletheia.substrate.TransactionLayerSql;
import de.visterion.aletheia.tagrules.RuleAction;
import de.visterion.aletheia.tagrules.RuleCondition;
import de.visterion.aletheia.tagrules.TagRuleResolver;
import de.visterion.aletheia.tagrules.TagRuleValidator;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * MCP write tools (spec §5 "Write", scope {@code write}). Every tool name here matches {@code
 * ToolPermissionService.WRITE_TOOLS} exactly.
 *
 * <p>Every write additionally appends a {@code counterparty_history} row (spec §4/§5 -- an
 * append-only audit log). {@code source=auto} never sets {@code counterparties.reviewed} or
 * flips {@code status} to {@code confirmed}: {@link #classifyCounterparty} and {@link
 * #markRecurring} only ever touch {@code counterparty_tags}/{@code recurring} plus history --
 * {@code counterparties.status}/{@code reviewed} are exclusively owned by {@link
 * #confirmCounterparty} and {@link #dismissCounterparty} (spec §5, "the workflow").
 */
@Component
public class WriteTools {

  private static final Logger log = LoggerFactory.getLogger(WriteTools.class);

  private static final int MAX_BATCH_SIZE = 1000;
  private static final int CONFIRM_REQUIRED_THRESHOLD = 200;

  private final DSLContext db;
  private final CounterpartySelectorResolver selectorResolver;
  private final TransactionSplitService splitService;
  private final OperatingGuideService operatingGuideService;
  private final CounterpartyResolver counterpartyResolver;
  private final ContractResolver contractResolver;
  private final SubstrateLock substrateLock;
  private final TagRuleResolver tagRuleResolver;
  private final CounterpartyMergeService mergeService;
  private final TransactionTemplate txTemplate;

  public WriteTools(
      DSLContext db,
      CounterpartySelectorResolver selectorResolver,
      TransactionSplitService splitService,
      OperatingGuideService operatingGuideService,
      CounterpartyResolver counterpartyResolver,
      ContractResolver contractResolver,
      SubstrateLock substrateLock,
      TagRuleResolver tagRuleResolver,
      CounterpartyMergeService mergeService,
      PlatformTransactionManager txManager) {
    this.db = db;
    this.selectorResolver = selectorResolver;
    this.splitService = splitService;
    this.operatingGuideService = operatingGuideService;
    this.counterpartyResolver = counterpartyResolver;
    this.contractResolver = contractResolver;
    this.substrateLock = substrateLock;
    this.tagRuleResolver = tagRuleResolver;
    this.mergeService = mergeService;
    this.txTemplate = new TransactionTemplate(txManager);
  }

  public String updatePreferences(
      String preferences) {
    return operatingGuideService.updatePreferences(preferences, currentActor());
  }

  @Transactional
  public BatchWriteAck classifyCounterparty(
      List<Long> counterpartyIds,
      CounterpartySelector where,
      List<TagInput> tags,
      TagSource source,
      BigDecimal confidence,
      Boolean confirm) {
    boolean effectiveConfirm = Boolean.TRUE.equals(confirm);
    List<Long> ids = resolveTargetIds(counterpartyIds, where);
    enforceBatchCaps(ids, effectiveConfirm);

    if (tags == null || tags.isEmpty()) {
      return new BatchWriteAck(0, List.of());
    }

    // "Set/replace" (spec §5): for every dimension present in the request, drop the existing
    // tags for that (counterparty, dimension) and insert the new ones -- not a per-row upsert,
    // since `value` is part of the primary key and a replace must be able to drop stale values.
    List<String> dimensions = tags.stream().map(TagInput::dimension).distinct().toList();
    for (long counterpartyId : ids) {
      requireExistingCounterparty(counterpartyId);
      for (String dimension : dimensions) {
        List<String> oldValues =
            db.select(COUNTERPARTY_TAGS.VALUE)
                .from(COUNTERPARTY_TAGS)
                .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId))
                .and(COUNTERPARTY_TAGS.DIMENSION.eq(dimension))
                .fetch(COUNTERPARTY_TAGS.VALUE);

        db.deleteFrom(COUNTERPARTY_TAGS)
            .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId))
            .and(COUNTERPARTY_TAGS.DIMENSION.eq(dimension))
            .execute();

        List<String> newValues =
            tags.stream()
                .filter(t -> t.dimension().equals(dimension))
                .map(TagInput::value)
                .toList();
        for (String value : newValues) {
          db.insertInto(COUNTERPARTY_TAGS)
              .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, counterpartyId)
              .set(COUNTERPARTY_TAGS.DIMENSION, dimension)
              .set(COUNTERPARTY_TAGS.VALUE, value)
              .set(COUNTERPARTY_TAGS.SOURCE, source.name())
              .set(COUNTERPARTY_TAGS.CONFIDENCE, confidence)
              .execute();
        }

        insertHistory(
            counterpartyId,
            "tag:" + dimension,
            String.join(",", oldValues),
            String.join(",", newValues),
            source.name());
      }
    }

    return new BatchWriteAck(ids.size(), dimensions);
  }

  /**
   * Resolves the batch target id set: explicit {@code counterpartyIds} win if non-empty, else the
   * {@code where}-selector is resolved; rejects if neither is supplied.
   */
  private List<Long> resolveTargetIds(List<Long> counterpartyIds, CounterpartySelector where) {
    if (counterpartyIds != null && !counterpartyIds.isEmpty()) {
      return counterpartyIds.stream().distinct().toList();
    }
    if (where != null) {
      return selectorResolver.resolve(where);
    }
    throw new IllegalArgumentException(
        "either counterpartyIds or where must be supplied to select a target");
  }

  /**
   * Safety caps on the resolved batch size (spec §5, HiveMem-style guardrails): more than {@link
   * #MAX_BATCH_SIZE} is always rejected (even with {@code confirm=true}); {@link
   * #CONFIRM_REQUIRED_THRESHOLD} or more requires an explicit {@code confirm=true}.
   */
  private void enforceBatchCaps(List<Long> ids, boolean confirm) {
    int count = ids.size();
    if (count > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "selector/ids resolved to " + count + " matches, narrow the selector (max " + MAX_BATCH_SIZE + ")");
    }
    if (count >= CONFIRM_REQUIRED_THRESHOLD && !confirm) {
      throw new IllegalArgumentException(
          count + " matches; pass confirm=true to run a batch this large");
    }
  }

  public WriteAck markRecurring(
      long counterpartyId,
      Long contractId,
      Cadence cadence,
      BigDecimal typicalAmount,
      BigDecimal amountMin,
      BigDecimal amountMax,
      TagSource source,
      BigDecimal confidence) {
    requireExistingCounterparty(counterpartyId);

    org.jooq.Condition key =
        RECURRING
            .COUNTERPARTY_ID
            .eq(counterpartyId)
            .and(
                contractId == null
                    ? RECURRING.CONTRACT_ID.isNull()
                    : RECURRING.CONTRACT_ID.eq(contractId));

    BigDecimal oldTypicalAmount =
        db.select(RECURRING.TYPICAL_AMOUNT).from(RECURRING).where(key).fetchOne(RECURRING.TYPICAL_AMOUNT);

    int affected =
        db.insertInto(RECURRING)
            .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
            .set(RECURRING.CONTRACT_ID, contractId)
            .set(RECURRING.CADENCE, cadence.name())
            .set(RECURRING.TYPICAL_AMOUNT, typicalAmount)
            .set(RECURRING.AMOUNT_MIN, amountMin)
            .set(RECURRING.AMOUNT_MAX, amountMax)
            .set(RECURRING.SOURCE, source.name())
            .set(RECURRING.CONFIDENCE, confidence)
            .onConflict(RECURRING.COUNTERPARTY_ID, RECURRING.CONTRACT_ID)
            .doUpdate()
            .set(RECURRING.CADENCE, cadence.name())
            .set(RECURRING.TYPICAL_AMOUNT, typicalAmount)
            .set(RECURRING.AMOUNT_MIN, amountMin)
            .set(RECURRING.AMOUNT_MAX, amountMax)
            .set(RECURRING.SOURCE, source.name())
            .set(RECURRING.CONFIDENCE, confidence)
            // GUARD: an auto-source call can never overwrite a row a human already confirmed.
            .where(RECURRING.SOURCE.eq("auto"))
            .execute();

    // Postgres returns 0 rows affected when the ON CONFLICT DO UPDATE ... WHERE guard matches no
    // row (i.e. the existing row is confirmed and this call is auto) -- a suppressed no-op, so
    // recording history here would be a phantom old->new change.
    if (affected > 0) {
      insertHistory(
          counterpartyId,
          "recurring",
          oldTypicalAmount == null ? null : oldTypicalAmount.toPlainString(),
          typicalAmount == null ? null : typicalAmount.toPlainString(),
          source.name());
    }

    if (affected == 0) {
      return new WriteAck(counterpartyId, "no change: recurring series is confirmed");
    }

    return new WriteAck(counterpartyId, "recurring series set to " + cadence.name());
  }

  @Transactional
  public BatchWriteAck confirmCounterparty(
      Long counterpartyId,
      Long contractId,
      List<Long> counterpartyIds,
      CounterpartySelector where,
      Boolean confirm) {
    List<Long> batchIds =
        resolveBatchTarget(
            counterpartyId, contractId, counterpartyIds, where, Boolean.TRUE.equals(confirm));
    if (batchIds != null) {
      for (long id : batchIds) {
        confirmOne(id, null);
      }
      return new BatchWriteAck(
          batchIds.size(), List.of("confirmed " + batchIds.size() + " counterparties"));
    }
    if (counterpartyId == null) {
      throw new IllegalArgumentException(
          "supply counterpartyId, counterpartyIds, or a non-empty where");
    }
    return new BatchWriteAck(1, List.of(confirmOne(counterpartyId, contractId)));
  }

  /**
   * The former single-item confirm body, returning its message. If {@code contractId} is given,
   * confirms just that contract: {@code status='confirmed'}, {@code source='confirmed'}, {@code
   * confirmed_at=now()}, and its linked recurring row's {@code source='confirmed'} -- the
   * counterparty's tags/reviewed flag are untouched. If {@code contractId} is omitted and the
   * counterparty has a mandate-less auto recurring row ({@code contract_id IS NULL}), that series
   * is materialized into a NULL-mandate contract first and confirmed the same way. Otherwise (no
   * contractId, no mandate-less series): legacy behavior -- flip this counterparty's auto
   * tags/recurring to confirmed, set {@code reviewed=true} and {@code status='confirmed'}.
   */
  private String confirmOne(long counterpartyId, Long contractId) {
    String oldStatus = requireExistingCounterparty(counterpartyId);

    if (contractId != null || hasMandatelessAutoRecurring(counterpartyId)) {
      long targetContract;
      if (contractId != null) {
        requireContractOwnedBy(counterpartyId, contractId);
        targetContract = contractId;
      } else {
        targetContract = materializeMandatelessContract(counterpartyId);
      }
      confirmContractRow(counterpartyId, targetContract);
      return "contract " + targetContract + " confirmed";
    }

    db.update(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId))
        .and(COUNTERPARTY_TAGS.SOURCE.eq("auto"))
        .execute();

    // Only counterparty-level (mandate-less) recurring rows belong to this legacy path --
    // mandate-linked rows (contract_id set) are exclusively owned by the per-contract confirm
    // path above and must not be silently flipped here (a split counterparty whose recurring is
    // entirely mandate-linked would otherwise desync recurring.source='confirmed' from
    // contracts.status='open').
    db.update(RECURRING)
        .set(RECURRING.SOURCE, "confirmed")
        .where(RECURRING.COUNTERPARTY_ID.eq(counterpartyId))
        .and(RECURRING.CONTRACT_ID.isNull())
        .and(RECURRING.SOURCE.eq("auto"))
        .execute();

    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.REVIEWED, true)
        .set(COUNTERPARTIES.STATUS, "confirmed")
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .execute();

    insertHistory(counterpartyId, "status", oldStatus, "confirmed", "confirmed");

    return "confirmed";
  }

  private void confirmContractRow(long counterpartyId, long contractId) {
    String oldStatus =
        db.select(CONTRACTS.STATUS)
            .from(CONTRACTS)
            .where(CONTRACTS.ID.eq(contractId))
            .fetchOne(CONTRACTS.STATUS);

    db.update(CONTRACTS)
        .set(CONTRACTS.STATUS, "confirmed")
        .set(CONTRACTS.SOURCE, "confirmed")
        .set(CONTRACTS.CONFIRMED_AT, java.time.OffsetDateTime.now())
        .where(CONTRACTS.ID.eq(contractId))
        .execute();

    db.update(RECURRING)
        .set(RECURRING.SOURCE, "confirmed")
        .where(RECURRING.CONTRACT_ID.eq(contractId))
        .execute();

    insertHistory(counterpartyId, "contract:" + contractId, oldStatus, "confirmed", "confirmed");
  }

  /**
   * True if {@code counterpartyId} has a recurring row with no linked contract ({@code
   * contract_id IS NULL}) -- i.e. an obligation that surfaced without a mandate (recurring debits
   * that never carry a {@code mandate_id}, spec §5) and has not yet had its contract row
   * materialized by a per-contract confirm/dismiss/link.
   */
  private boolean hasMandatelessAutoRecurring(long counterpartyId) {
    return db.fetchExists(
        db.selectOne()
            .from(RECURRING)
            .where(RECURRING.COUNTERPARTY_ID.eq(counterpartyId))
            .and(RECURRING.CONTRACT_ID.isNull()));
  }

  /**
   * Lazily materializes the NULL-mandate ({@code mandate_id IS NULL}) contract row for a
   * counterparty's mandate-less recurring series, and repoints that recurring row at it.
   * Idempotent: if a NULL-mandate contract already exists for this counterparty, its id is
   * returned instead of inserting a duplicate (the {@code uq_contract_counterparty_mandate}
   * unique key, V9, treats NULLs as not distinct, so a second insert would violate it anyway).
   */
  private long materializeMandatelessContract(long counterpartyId) {
    Long existing =
        db.select(CONTRACTS.ID)
            .from(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
            .and(CONTRACTS.MANDATE_ID.isNull())
            .fetchOne(CONTRACTS.ID);
    if (existing != null) {
      return existing;
    }
    long id =
        db.insertInto(CONTRACTS)
            .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
            .set(CONTRACTS.MANDATE_ID, (String) null)
            .set(CONTRACTS.SOURCE, "auto")
            .set(CONTRACTS.STATUS, "open")
            .returning(CONTRACTS.ID)
            .fetchOne()
            .getId();
    // Point the counterparty's mandate-less recurring row at this newly materialized contract.
    db.update(RECURRING)
        .set(RECURRING.CONTRACT_ID, id)
        .where(RECURRING.COUNTERPARTY_ID.eq(counterpartyId))
        .and(RECURRING.CONTRACT_ID.isNull())
        .execute();
    return id;
  }

  public WriteAck linkContract(
      long contractId,
      String hivememCellId,
      String notes) {
    Long counterpartyId =
        db.select(CONTRACTS.COUNTERPARTY_ID)
            .from(CONTRACTS)
            .where(CONTRACTS.ID.eq(contractId))
            .fetchOne(CONTRACTS.COUNTERPARTY_ID);
    if (counterpartyId == null) {
      throw new IllegalArgumentException("no such contract: " + contractId);
    }

    String oldCellId =
        db.select(CONTRACTS.HIVEMEM_CELL_ID)
            .from(CONTRACTS)
            .where(CONTRACTS.ID.eq(contractId))
            .fetchOne(CONTRACTS.HIVEMEM_CELL_ID);

    var update = db.update(CONTRACTS).set(CONTRACTS.HIVEMEM_CELL_ID, hivememCellId);
    // Only overwrite NOTES when a value is actually supplied -- notes=null on a re-link means
    // "unchanged", not "clear the existing notes".
    if (notes != null) {
      update = update.set(CONTRACTS.NOTES, notes);
    }
    update.where(CONTRACTS.ID.eq(contractId)).execute();

    insertHistory(counterpartyId, "contract", oldCellId, hivememCellId, "confirmed");

    return new WriteAck(
        counterpartyId, "linked contract " + contractId + " to HiveMem cell " + hivememCellId);
  }

  @Transactional
  public BatchWriteAck dismissCounterparty(
      Long counterpartyId,
      Long contractId,
      List<Long> counterpartyIds,
      CounterpartySelector where,
      String reason,
      Boolean confirm) {
    List<Long> batchIds =
        resolveBatchTarget(
            counterpartyId, contractId, counterpartyIds, where, Boolean.TRUE.equals(confirm));
    if (batchIds != null) {
      for (long id : batchIds) {
        dismissOne(id, reason, null);
      }
      return new BatchWriteAck(
          batchIds.size(),
          List.of("dismissed " + batchIds.size() + " counterparties: " + reason));
    }
    if (counterpartyId == null) {
      throw new IllegalArgumentException(
          "supply counterpartyId, counterpartyIds, or a non-empty where");
    }
    return new BatchWriteAck(1, List.of(dismissOne(counterpartyId, reason, contractId)));
  }

  /**
   * The former single-item dismiss body, returning its message. Sets {@code reviewed=true} when
   * the caller supplied no {@code contractId} (counterparty-level intent -- both branches).
   */
  private String dismissOne(long counterpartyId, String reason, Long callerContractId) {
    String oldStatus = requireExistingCounterparty(counterpartyId);
    boolean callerContract = callerContractId != null;

    if (callerContractId != null || hasMandatelessAutoRecurring(counterpartyId)) {
      long targetContract =
          callerContractId != null
              ? requireContractOwnedByReturning(counterpartyId, callerContractId)
              : materializeMandatelessContract(counterpartyId);
      String oldContractStatus =
          db.select(CONTRACTS.STATUS)
              .from(CONTRACTS)
              .where(CONTRACTS.ID.eq(targetContract))
              .fetchOne(CONTRACTS.STATUS);
      db.update(CONTRACTS)
          .set(CONTRACTS.STATUS, "dismissed")
          .set(CONTRACTS.DISMISSED_REASON, reason)
          .where(CONTRACTS.ID.eq(targetContract))
          .execute();
      insertHistory(
          counterpartyId,
          "contract:" + targetContract,
          oldContractStatus,
          "dismissed",
          "confirmed");
      if (!callerContract) {
        db.update(COUNTERPARTIES)
            .set(COUNTERPARTIES.REVIEWED, true)
            .where(COUNTERPARTIES.ID.eq(counterpartyId))
            .execute();
      }
      return "contract " + targetContract + " dismissed: " + reason;
    }

    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.STATUS, "dismissed")
        .set(COUNTERPARTIES.DISMISSED_REASON, reason)
        .set(COUNTERPARTIES.REVIEWED, true) // m5: legacy branch is always a no-contractId call
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .execute();
    insertHistory(counterpartyId, "status", oldStatus, "dismissed", "confirmed");
    return "dismissed: " + reason;
  }

  /**
   * Returns the resolved batch target ids, or {@code null} for single-item mode. Enforces the
   * mode-exclusivity rules, the zero-effective-conditions guard, and the batch caps.
   */
  private List<Long> resolveBatchTarget(
      Long counterpartyId,
      Long contractId,
      List<Long> counterpartyIds,
      CounterpartySelector where,
      boolean confirm) {
    boolean hasIds = counterpartyIds != null && !counterpartyIds.isEmpty();
    boolean hasWhere = where != null;
    if (!hasIds && !hasWhere) {
      return null; // single-item mode
    }
    if (contractId != null) {
      throw new IllegalArgumentException("contractId is single-item only, not for a batch");
    }
    if (counterpartyId != null) {
      throw new IllegalArgumentException(
          "supply either counterpartyId or a batch target, not both");
    }
    if (hasIds && hasWhere) {
      throw new IllegalArgumentException("supply either counterpartyIds or where, not both");
    }
    if (hasWhere && !hasEffectiveCondition(where)) {
      throw new IllegalArgumentException(
          "where must contribute at least one filter (refusing a whole-table match)");
    }
    List<Long> ids = resolveTargetIds(counterpartyIds, where);
    enforceBatchCaps(ids, confirm);
    return ids;
  }

  private static boolean hasEffectiveCondition(CounterpartySelector w) {
    return Boolean.TRUE.equals(w.untagged())
        || (w.namePattern() != null && !w.namePattern().isBlank())
        || w.predominantDirection() != null
        || (w.minAnnualCost() != null && w.minAnnualCost().signum() > 0)
        || (w.domainIn() != null && !w.domainIn().isEmpty())
        || (w.natureIn() != null && !w.natureIn().isEmpty())
        || w.reviewed() != null
        || w.hasContract() != null
        || w.txnCountMax() != null
        || (w.natureNotIn() != null && !w.natureNotIn().isEmpty())
        || (w.domainNotIn() != null && !w.domainNotIn().isEmpty())
        || w.amountMin() != null
        || w.amountMax() != null
        || w.lastSeenBefore() != null
        || w.lastSeenAfter() != null;
  }

  /**
   * Guards the per-contract confirm/dismiss paths (spec §5): a caller-supplied {@code
   * contractId} must exist and must belong to the {@code counterpartyId} it was called with --
   * otherwise {@code confirm_counterparty(cpA, contractOfCpB)} could confirm another
   * counterparty's contract while writing the history row under {@code cpA}, or a nonexistent
   * {@code contractId} could update 0 rows yet still ack success.
   */
  private void requireContractOwnedBy(long counterpartyId, long contractId) {
    requireContractOwnedByReturning(counterpartyId, contractId);
  }

  /** Same validation as {@link #requireContractOwnedBy}, returning {@code contractId}. */
  private long requireContractOwnedByReturning(long counterpartyId, long contractId) {
    Long ownerId =
        db.select(CONTRACTS.COUNTERPARTY_ID)
            .from(CONTRACTS)
            .where(CONTRACTS.ID.eq(contractId))
            .fetchOne(CONTRACTS.COUNTERPARTY_ID);
    if (ownerId == null) {
      throw new IllegalArgumentException("contract " + contractId + " not found");
    }
    if (ownerId != counterpartyId) {
      throw new IllegalArgumentException(
          "contract " + contractId + " belongs to a different counterparty");
    }
    return contractId;
  }

  /**
   * Confirms {@code counterpartyId} exists and is not folded (spec §Task 4, alias merge): a
   * counterparty with {@code merged_into IS NOT NULL} is a soft-deleted source that reads already
   * hide, so no write may target it either -- callers must resolve to the canonical id first.
   */
  private String requireExistingCounterparty(long counterpartyId) {
    var row =
        db.select(COUNTERPARTIES.STATUS, COUNTERPARTIES.MERGED_INTO)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(counterpartyId))
            .fetchOne();
    if (row == null) {
      throw new IllegalArgumentException("no such counterparty: " + counterpartyId);
    }
    Long mergedInto = row.get(COUNTERPARTIES.MERGED_INTO);
    if (mergedInto != null) {
      throw new IllegalArgumentException(
          "counterparty "
              + counterpartyId
              + " has been merged into "
              + mergedInto
              + "; use the canonical id");
    }
    return row.get(COUNTERPARTIES.STATUS);
  }

  private void insertHistory(
      long counterpartyId, String field, String oldValue, String newValue, String source) {
    db.insertInto(COUNTERPARTY_HISTORY)
        .set(COUNTERPARTY_HISTORY.COUNTERPARTY_ID, counterpartyId)
        .set(COUNTERPARTY_HISTORY.FIELD, field)
        .set(COUNTERPARTY_HISTORY.OLD_VALUE, oldValue)
        .set(COUNTERPARTY_HISTORY.NEW_VALUE, newValue)
        .set(COUNTERPARTY_HISTORY.SOURCE, source)
        .set(COUNTERPARTY_HISTORY.ACTOR, currentActor())
        .execute();
  }

  /**
   * The calling principal's name, resolved from the request attribute {@link AuthFilter} sets
   * (mirrors how {@code ScopeEnforcingToolCallback} resolves the caller). Falls back to {@code
   * "unknown"} for direct (non-HTTP) callers such as unit/integration tests that invoke this
   * bean's methods without a request in flight.
   */
  private static String currentActor() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return "unknown";
    }
    Object principal =
        attributes.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return principal instanceof AuthPrincipal authPrincipal ? authPrincipal.name() : "unknown";
  }

  // --- reattribute_transaction (#43 manual attribution) ---

  private static final int MAX_REFS = 1000;

  // language=SQL — classify each ref: found? split child? superseded parent? (for precise errors)
  private static final String VALIDATE_REFS_SQL =
      """
      SELECT r.content_hash AS ch,
             r.occurrence_index AS oi,
             (t.content_hash IS NOT NULL) AS found,
             (t.split_parent_content_hash IS NOT NULL) AS is_child,
             """
          + "(t.content_hash IS NOT NULL AND NOT ("
          + TransactionLayerSql.notExistsSupersededParent("t")
          + ")) AS is_parent\n"
          + """
      FROM unnest(CAST(? AS text[]), CAST(? AS int[])) AS r(content_hash, occurrence_index)
      LEFT JOIN transactions t
             ON t.content_hash = r.content_hash AND t.occurrence_index = r.occurrence_index
      """;

  // language=SQL — atomic match+update; returns (matched active roots, rows actually changed).
  private static final String REATTRIBUTE_SQL =
      """
      WITH refs AS (
          SELECT * FROM unnest(CAST(? AS text[]), CAST(? AS int[]))
                   AS r(content_hash, occurrence_index)
      ),
      matched AS (
          SELECT t.id
          FROM transactions t
          JOIN refs r ON r.content_hash = t.content_hash
                     AND r.occurrence_index = t.occurrence_index
          WHERE t.split_parent_content_hash IS NULL
            AND """
          + " "
          + TransactionLayerSql.notExistsSupersededParent("t")
          + """

      ),
      upd AS (
          UPDATE transactions t
          SET attributed_name = CAST(? AS text), attribution_source = CAST(? AS text)
          FROM matched m
          WHERE t.id = m.id
            AND (t.attributed_name, t.attribution_source)
                IS DISTINCT FROM (CAST(? AS text), CAST(? AS text))
          RETURNING 1
      )
      SELECT (SELECT count(*) FROM matched) AS matched,
             (SELECT count(*) FROM upd)     AS changed
      """;

  public BatchWriteAck reattributeTransaction(
      List<TxReference> refs,
      String attributedName) {
    if (refs == null || refs.isEmpty()) {
      throw new IllegalArgumentException("refs must be non-empty");
    }
    List<TxReference> distinct = new java.util.ArrayList<>(new LinkedHashSet<>(refs));
    if (distinct.size() > MAX_REFS) {
      throw new IllegalArgumentException("too many refs (max " + MAX_REFS + ")");
    }

    // clear-mode iff attributedName is exactly null; a blank/whitespace name is a 400, not a clear.
    String setName = null;
    String setSource = null;
    if (attributedName != null) {
      String trimmed = attributedName.strip();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException(
            "attributedName must not be blank; pass null to clear");
      }
      setName = trimmed;
      setSource = "manual";
    }

    String[] hashes = distinct.stream().map(TxReference::contentHash).toArray(String[]::new);
    Integer[] indices =
        distinct.stream().map(TxReference::occurrenceIndex).toArray(Integer[]::new);

    validateRefsAreActiveRoots(hashes, indices);

    substrateLock.lock();
    try {
      org.jooq.Record counts =
          db.fetchOne(
              REATTRIBUTE_SQL,
              DSL.array(hashes),
              DSL.array(indices),
              setName,
              setSource,
              setName,
              setSource);
      int matched = counts.get("matched", Integer.class);
      int changed = counts.get("changed", Integer.class);
      // Not @Transactional (shared-lock design): a ref superseded by a concurrent split between
      // pre-lock validation and this UPDATE is excluded from `matched`, so `changed` rows are
      // already committed here; we then reject with a retry hint. Benign, self-heals next resolve.
      if (matched != distinct.size()) {
        throw new IllegalArgumentException(
            "a target changed underneath the call (concurrent split?); retry");
      }
      counterpartyResolver.resolve();
      contractResolver.resolve();
      try {
        tagRuleResolver.resolve();
      } catch (RuntimeException e) {
        // attribution already committed; a rule failure must not fail the reattribution
        org.slf4j.LoggerFactory.getLogger(WriteTools.class)
            .warn("Auto-tagging rules failed after reattribution; will retry next pass: {}", e.toString());
      }
      String message =
          setName == null
              ? "cleared attribution on " + changed + " booking(s)"
              : "reattributed " + changed + " booking(s) to '" + setName + "'";
      return new BatchWriteAck(changed, List.of(message));
    } finally {
      substrateLock.unlock();
    }
  }

  /** Precise per-ref validation (exists / not a split child / not a superseded parent). */
  private void validateRefsAreActiveRoots(String[] hashes, Integer[] indices) {
    var rows = db.fetch(VALIDATE_REFS_SQL, DSL.array(hashes), DSL.array(indices));
    for (var row : rows) {
      String ref = row.get("ch", String.class) + "#" + row.get("oi", Integer.class);
      if (!Boolean.TRUE.equals(row.get("found", Boolean.class))) {
        throw new IllegalArgumentException("no such transaction: " + ref);
      }
      if (Boolean.TRUE.equals(row.get("is_child", Boolean.class))) {
        throw new IllegalArgumentException("cannot reattribute a split child: " + ref);
      }
      if (Boolean.TRUE.equals(row.get("is_parent", Boolean.class))) {
        throw new IllegalArgumentException(
            "cannot reattribute a split parent (it has been split into children): " + ref);
      }
    }
  }

  // --- merge_counterparty (counterparty-merge-alias design, sub-project A) ---

  /**
   * Folds {@code sourceIds} into {@code targetId} (spec §3): guards, then the merge core runs in
   * its own transaction under {@link #substrateLock} (mirrors {@link #reattributeTransaction}
   * exactly -- NOT {@code @Transactional}); after it commits, the resolvers settle outside that
   * transaction, still under the lock, with {@link TagRuleResolver} wrapped in try/catch so one
   * failing rule cannot fail an otherwise-successful merge.
   */
  public BatchWriteAck mergeCounterparty(long targetId, List<Long> sourceIds, String reason) {
    substrateLock.lock();
    try {
      Integer merged = txTemplate.execute(status -> mergeService.mergeCore(targetId, sourceIds, reason));
      counterpartyResolver.resolve();
      contractResolver.resolve();
      try {
        tagRuleResolver.resolve();
      } catch (RuntimeException e) {
        log.warn("Auto-tagging rules failed after merge; will retry next pass: {}", e.toString());
      }
      return new BatchWriteAck(
          merged, List.of("merged " + merged + " counterparty(ies) into " + targetId));
    } finally {
      substrateLock.unlock();
    }
  }

  // --- split_transaction (Phase 2 core) ---

  public SplitTransactionAck splitTransaction(
      TxReference tx,
      List<Allocation> allocations,
      Boolean unsplit) {
    return splitService.splitTransaction(tx, allocations, unsplit);
  }

  // --- tag rules (#37 auto-tagging rules) ---

  public CreateTagRuleAck createTagRule(
      String name,
      List<RuleCondition> conditions,
      List<RuleAction> actions,
      Boolean dryRun,
      Boolean backfill,
      Boolean confirm) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    TagRuleValidator.validate(conditions, actions);

    List<Long> matched = tagRuleResolver.matchedCounterpartyIds(conditions);
    List<String> wouldSetTags = actions.stream().map(a -> a.dimension() + "=" + a.value()).toList();

    if (Boolean.TRUE.equals(dryRun)) {
      List<CounterpartySample> samples =
          db
              .select(
                  COUNTERPARTIES.ID,
                  DSL.coalesce(COUNTERPARTIES.DISPLAY_NAME_OVERRIDE, COUNTERPARTIES.DISPLAY_NAME))
              .from(COUNTERPARTIES)
              .where(COUNTERPARTIES.ID.in(matched))
              .limit(20)
              .fetch(r -> new CounterpartySample(r.value1(), r.value2()));
      int wouldChange = tagRuleResolver.countWouldChange(matched, actions);
      return new CreateTagRuleAck(null, matched.size(), wouldChange, 0, samples, wouldSetTags);
    }

    if (matched.size() >= CONFIRM_REQUIRED_THRESHOLD && !Boolean.TRUE.equals(confirm)) {
      throw new IllegalArgumentException(
          matched.size() + " counterparties match; dry-run then pass confirm=true to apply a rule this broad");
    }

    TagRuleResolver.RuleCreateResult result =
        tagRuleResolver.createRule(name, conditions, actions, Boolean.TRUE.equals(backfill));
    return new CreateTagRuleAck(
        result.ruleId(),
        matched.size(),
        result.appliedCount(),
        result.appliedCount(),
        List.of(),
        wouldSetTags);
  }

  public TagRuleAck setTagRuleEnabled(
      Long ruleId,
      Boolean enabled) {
    int updated =
        db.update(de.visterion.aletheia.jooq.Tables.TAG_RULES)
            .set(de.visterion.aletheia.jooq.Tables.TAG_RULES.ENABLED, Boolean.TRUE.equals(enabled))
            .where(de.visterion.aletheia.jooq.Tables.TAG_RULES.ID.eq(ruleId))
            .execute();
    if (updated == 0) {
      throw new IllegalArgumentException("no such tag rule: " + ruleId);
    }
    return new TagRuleAck(ruleId, Boolean.TRUE.equals(enabled) ? "enabled" : "disabled");
  }

  public TagRuleAck deleteTagRule(Long ruleId) {
    int deleted =
        db.deleteFrom(de.visterion.aletheia.jooq.Tables.TAG_RULES)
            .where(de.visterion.aletheia.jooq.Tables.TAG_RULES.ID.eq(ruleId))
            .execute();
    if (deleted == 0) {
      throw new IllegalArgumentException("no such tag rule: " + ruleId);
    }
    return new TagRuleAck(ruleId, "deleted");
  }

}
