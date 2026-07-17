package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import java.math.BigDecimal;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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

  private static final int MAX_BATCH_SIZE = 1000;
  private static final int CONFIRM_REQUIRED_THRESHOLD = 200;

  private final DSLContext db;
  private final CounterpartySelectorResolver selectorResolver;
  private final TransactionSplitService splitService;
  private final OperatingGuideService operatingGuideService;

  public WriteTools(
      DSLContext db,
      CounterpartySelectorResolver selectorResolver,
      TransactionSplitService splitService,
      OperatingGuideService operatingGuideService) {
    this.db = db;
    this.selectorResolver = selectorResolver;
    this.splitService = splitService;
    this.operatingGuideService = operatingGuideService;
  }

  @Tool(
      name = "update_preferences",
      description =
          "Record durable customer preferences (markdown). Replaces the preferences section only"
              + " -- the operating guide is protected. wake_up first, edit, write back the full"
              + " preferences markdown.")
  public String updatePreferences(
      @ToolParam(description = "the full new preferences markdown") String preferences) {
    return operatingGuideService.updatePreferences(preferences, currentActor());
  }

  @Tool(
      name = "classify_counterparty",
      description =
          "Set/replace the tags for one or more dimensions on a batch of counterparties (explicit"
              + " ids or a where-selector). Never sets counterparties.reviewed or status -- only"
              + " confirm/dismiss do that. Batches of 200+ require confirm=true; batches over"
              + " 1000 are always rejected.")
  @Transactional
  public BatchWriteAck classifyCounterparty(
      @ToolParam(description = "explicit counterparties.id list, optional", required = false)
          List<Long> counterpartyIds,
      @ToolParam(description = "selector to resolve target ids, optional", required = false)
          CounterpartySelector where,
      @ToolParam(description = "the {dimension, value} pairs to set") List<TagInput> tags,
      @ToolParam(description = "provenance of this classification") TagSource source,
      @ToolParam(description = "0..1, optional", required = false) BigDecimal confidence,
      @ToolParam(
              description = "must be true to run a batch of 200 or more",
              required = false)
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

  @Tool(
      name = "mark_recurring",
      description =
          "Record/replace the recurring series for a counterparty, keyed by (counterparty_id,"
              + " contract_id) -- pass contractId (a contracts.id) to target that specific"
              + " contract's series, or null for the counterparty's mandate-less series. An"
              + " auto-source call can never overwrite an already-confirmed row. Never sets"
              + " counterparties.reviewed or status. Note: on a MANDATE contract (a contractId"
              + " whose contracts row has a mandate_id), measured values such as typical_amount"
              + " are refreshed from transactions by ContractResolver on every startup, so a"
              + " manual override here is not durable for mandate contracts -- mandate-less"
              + " series (contractId=null) are not resolver-owned.")
  public WriteAck markRecurring(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(
              description = "contracts.id to target, or null for the mandate-less series",
              required = false)
          Long contractId,
      @ToolParam(description = "recurrence interval") Cadence cadence,
      @ToolParam(description = "the representative amount per occurrence") BigDecimal typicalAmount,
      @ToolParam(description = "smallest observed amount, optional", required = false)
          BigDecimal amountMin,
      @ToolParam(description = "largest observed amount, optional", required = false)
          BigDecimal amountMax,
      @ToolParam(description = "provenance of this classification") TagSource source,
      @ToolParam(description = "0..1, optional", required = false) BigDecimal confidence) {
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

  @Tool(
      name = "confirm_counterparty",
      description =
          "The human's 'yes'. SINGLE: counterpartyId (+ optional contractId to confirm just that"
              + " contract). BATCH: counterpartyIds OR a where-selector (never both, never with"
              + " contractId/counterpartyId) -- each id is confirmed at counterparty level"
              + " (contractId=null): a mandate-less recurring series is materialized+confirmed"
              + " (appears in the obligations register); otherwise auto tags/status flip to"
              + " confirmed. An OPEN mandate contract is NOT confirmed by batch -- use single"
              + " confirm(id, contractId) for those. Batches of 200+ require confirm=true.")
  @Transactional
  public BatchWriteAck confirmCounterparty(
      @ToolParam(description = "counterparties.id (single-item mode)", required = false)
          Long counterpartyId,
      @ToolParam(description = "contracts.id to confirm (single-item only)", required = false)
          Long contractId,
      @ToolParam(description = "explicit ids (batch mode)", required = false)
          List<Long> counterpartyIds,
      @ToolParam(description = "where-selector (batch mode)", required = false)
          CounterpartySelector where,
      @ToolParam(description = "must be true for a batch of 200 or more", required = false)
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

  @Tool(
      name = "link_contract",
      description =
          "Link a contract to a HiveMem contract cell. contractId is a contracts.id -- get it"
              + " from list_unmatched_recurring/get_review_queue (for a mandate-less obligation,"
              + " confirm_counterparty/dismiss_counterparty without a contractId first to"
              + " materialize its contract row). Find the cell id via HiveMem:search with"
              + " where.realm=contracts (or the topic documenting the contract).")
  public WriteAck linkContract(
      @ToolParam(description = "contracts.id to link") long contractId,
      @ToolParam(description = "the HiveMem cell id") String hivememCellId,
      @ToolParam(description = "optional free-text notes", required = false) String notes) {
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

  @Tool(
      name = "dismiss_counterparty",
      description =
          "Mark counterparties as not-an-obligation. SINGLE: pass counterpartyId (optionally"
              + " contractId to dismiss just that contract). BATCH: pass counterpartyIds OR a"
              + " where-selector (never both, never with contractId/counterpartyId) -- each id is"
              + " dismissed at counterparty level (its mandate-less recurring series is"
              + " materialized+dismissed; a non-recurring counterparty gets status='dismissed')."
              + " A no-contractId dismiss also sets reviewed=true. Batches of 200+ require"
              + " confirm=true; over 1000 rejected. reason is required.")
  @Transactional
  public BatchWriteAck dismissCounterparty(
      @ToolParam(description = "counterparties.id (single-item mode)", required = false)
          Long counterpartyId,
      @ToolParam(description = "contracts.id to dismiss (single-item only)", required = false)
          Long contractId,
      @ToolParam(description = "explicit ids (batch mode)", required = false)
          List<Long> counterpartyIds,
      @ToolParam(description = "where-selector (batch mode)", required = false)
          CounterpartySelector where,
      @ToolParam(description = "why these were dismissed") String reason,
      @ToolParam(description = "must be true for a batch of 200 or more", required = false)
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
        || w.hasContract() != null;
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

  private String requireExistingCounterparty(long counterpartyId) {
    String status =
        db.select(COUNTERPARTIES.STATUS)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(counterpartyId))
            .fetchOne(COUNTERPARTIES.STATUS);
    if (status == null) {
      throw new IllegalArgumentException("no such counterparty: " + counterpartyId);
    }
    return status;
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

  // --- split_transaction (Phase 2 core) ---

  @Tool(
      name = "split_transaction",
      description =
          "Split an existing raw transaction into logical child positions (replace semantics)."
              + " If allocations is null/empty or unsplit=true, deletes all children (unsplit)."
              + " Otherwise validates that sum(allocations.amount) equals the original transaction"
              + " amount exactly and that each allocation.amount is strictly positive, deletes any"
              + " prior children for this parent, creates name-based counterparties on demand"
              + " (Bargeld auto gets nature=umbuchung), inserts deterministic synthetic child rows"
              + " with split_parent_* backrefs, import_id=null, occurrence_index=0, and attribution"
              + " driven from counterpartyId identity (creditor_id/iban/name) or displayName for"
              + " correct resolution on purchase vs pseudo parts. Idempotent replace. All inside"
              + " one transaction.")
  public SplitTransactionAck splitTransaction(
      @ToolParam(description = "reference to the parent transaction to split (by natural key)")
          TxReference tx,
      @ToolParam(
              description =
                  "list of target allocations; null/empty triggers unsplit. Each allocation targets"
                      + " either an existing counterpartyId or a displayName (to create name-based"
                      + " CP). Each allocation.amount must be strictly positive.",
              required = false)
          List<Allocation> allocations,
      @ToolParam(
              description = "if true force unsplit (delete children) even if allocations provided",
              required = false)
          Boolean unsplit) {
    return splitService.splitTransaction(tx, allocations, unsplit);
  }
}
