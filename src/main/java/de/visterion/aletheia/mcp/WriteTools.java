package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
  private static final String BARGELD_DISPLAY_NAME = "Bargeld";

  private final DSLContext db;
  private final CounterpartySelectorResolver selectorResolver;

  public WriteTools(DSLContext db, CounterpartySelectorResolver selectorResolver) {
    this.db = db;
    this.selectorResolver = selectorResolver;
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
          "The human's 'yes'. If contractId (a contracts.id) is given, confirms just that"
              + " contract: status='confirmed', source='confirmed', confirmed_at=now(), and its"
              + " linked recurring row's source='confirmed' -- the counterparty's tags/reviewed"
              + " flag are untouched. If contractId is omitted and the counterparty has a"
              + " mandate-less auto recurring row (contract_id IS NULL), that series is"
              + " materialized into a NULL-mandate contract first and confirmed the same way."
              + " Otherwise (no contractId, no mandate-less series): legacy behavior -- flip this"
              + " counterparty's auto tags/recurring to confirmed, set reviewed=true and"
              + " status='confirmed'. Drains the review queue for this counterparty.")
  public WriteAck confirmCounterparty(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(
              description = "contracts.id to confirm, optional -- see tool description",
              required = false)
          Long contractId) {
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
      return new WriteAck(counterpartyId, "contract " + targetContract + " confirmed");
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

    return new WriteAck(counterpartyId, "confirmed");
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
          "If contractId (a contracts.id) is given, dismisses just that contract:"
              + " status='dismissed', dismissed_reason=reason -- the counterparty's status is"
              + " untouched. If contractId is omitted and the counterparty has a mandate-less"
              + " auto recurring row, that series is materialized into a NULL-mandate contract"
              + " first and dismissed the same way. Otherwise (legacy): this counterparty is not"
              + " an obligation / not recurring: status='dismissed', dismissed_reason=reason.")
  public WriteAck dismissCounterparty(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(description = "why this counterparty was dismissed") String reason,
      @ToolParam(
              description = "contracts.id to dismiss, optional -- see tool description",
              required = false)
          Long contractId) {
    String oldStatus = requireExistingCounterparty(counterpartyId);

    if (contractId != null || hasMandatelessAutoRecurring(counterpartyId)) {
      long targetContract;
      if (contractId != null) {
        requireContractOwnedBy(counterpartyId, contractId);
        targetContract = contractId;
      } else {
        targetContract = materializeMandatelessContract(counterpartyId);
      }
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
      return new WriteAck(counterpartyId, "contract " + targetContract + " dismissed: " + reason);
    }

    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.STATUS, "dismissed")
        .set(COUNTERPARTIES.DISMISSED_REASON, reason)
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .execute();

    insertHistory(counterpartyId, "status", oldStatus, "dismissed", "confirmed");

    return new WriteAck(counterpartyId, "dismissed: " + reason);
  }

  /**
   * Guards the per-contract confirm/dismiss paths (spec §5): a caller-supplied {@code
   * contractId} must exist and must belong to the {@code counterpartyId} it was called with --
   * otherwise {@code confirm_counterparty(cpA, contractOfCpB)} could confirm another
   * counterparty's contract while writing the history row under {@code cpA}, or a nonexistent
   * {@code contractId} could update 0 rows yet still ack success.
   */
  private void requireContractOwnedBy(long counterpartyId, long contractId) {
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
              + " amount exactly, deletes any prior children for this parent, creates name-based"
              + " counterparties on demand (Bargeld auto gets nature=umbuchung), inserts"
              + " deterministic synthetic child rows with split_parent_* backrefs, import_id=null,"
              + " occurrence_index=0, and attribution copied/adjusted for correct resolution on"
              + " purchase vs pseudo parts. Idempotent replace. All inside one transaction.")
  @Transactional
  public SplitTransactionAck splitTransaction(
      @ToolParam(description = "reference to the parent transaction to split (by natural key)")
          TxReference tx,
      @ToolParam(
              description =
                  "list of target allocations; null/empty triggers unsplit. Each allocation targets"
                      + " either an existing counterpartyId or a displayName (to create name-based"
                      + " CP).",
              required = false)
          List<Allocation> allocations,
      @ToolParam(
              description = "if true force unsplit (delete children) even if allocations provided",
              required = false)
          Boolean unsplit) {
    if (tx == null || tx.contentHash() == null) {
      throw new IllegalArgumentException("tx reference with contentHash is required");
    }
    int occ = tx.occurrenceIndex(); // default 0 if record allows

    var parent =
        db.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.CONTENT_HASH.eq(tx.contentHash()))
            .and(TRANSACTIONS.OCCURRENCE_INDEX.eq(occ))
            .fetchOne();
    if (parent == null) {
      throw new IllegalArgumentException(
          "no such transaction: content_hash=" + tx.contentHash() + ", occurrence_index=" + occ);
    }
    if (parent.get(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH) != null) {
      throw new IllegalArgumentException(
          "cannot split a child transaction; only raw (root) rows may be split: content_hash="
              + tx.contentHash()
              + ", occurrence_index="
              + occ);
    }

    boolean doUnsplit =
        Boolean.TRUE.equals(unsplit) || allocations == null || allocations.isEmpty();

    if (doUnsplit) {
      int deleted =
          db.deleteFrom(TRANSACTIONS)
              .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(tx.contentHash()))
              .and(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX.eq(occ))
              .execute();
      return new SplitTransactionAck(
          true, 0, List.of(), "unsplit: removed " + deleted + " child row(s)");
    }

    // --- validate BEFORE any mutation ---
    BigDecimal orig = parent.get(TRANSACTIONS.AMOUNT);
    BigDecimal sum = BigDecimal.ZERO;
    for (Allocation a : allocations) {
      if (a.amount() == null || a.amount().signum() <= 0) {
        throw new IllegalArgumentException(
            "allocation amount must be positive, got: " + a.amount());
      }
      sum = sum.add(a.amount());
    }
    if (sum.compareTo(orig) != 0) {
      throw new IllegalArgumentException(
          "sum of allocations ("
              + sum
              + ") does not equal original transaction amount ("
              + orig
              + ")");
    }

    // --- mutate ---
    db.deleteFrom(TRANSACTIONS)
        .where(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(tx.contentHash()))
        .and(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX.eq(occ))
        .execute();

    List<Long> createdCpIds = new ArrayList<>();
    int created = 0;
    for (int i = 0; i < allocations.size(); i++) {
      Allocation a = allocations.get(i);
      String childHash = syntheticSplitHash(tx.contentHash(), i);

      Long cpId = a.counterpartyId();
      if (cpId != null) {
        requireExistingCounterparty(cpId);
      } else if (a.displayName() != null && !a.displayName().isBlank()) {
        // check pre-existence BEFORE ensure (for createdCpIds ack)
        String normForCheck = upperNormalize(a.displayName());
        Long existedBefore =
            db.select(COUNTERPARTIES.ID)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
                .and(COUNTERPARTIES.IDENTITY_VALUE.eq(normForCheck))
                .fetchOne(COUNTERPARTIES.ID);
        long ensured = ensureCounterpartyByDisplayName(a.displayName());
        if (existedBefore == null) {
          createdCpIds.add(ensured);
        }
        cpId = ensured;
      } else {
        throw new IllegalArgumentException(
            "allocation requires either counterpartyId or displayName");
      }

      String cpName;
      String credId;
      String iban;
      String mndt;
      if (a.counterpartyId() == null && a.displayName() != null) {
        String dn = a.displayName();
        boolean bargeld = dn.equalsIgnoreCase(BARGELD_DISPLAY_NAME);
        cpName = bargeld ? BARGELD_DISPLAY_NAME : trimNormalize(dn);
        credId = null;
        iban = null;
        // name-based: only explicit allocation mandate; never inherit parent mandate
        mndt = a.mandateId();
      } else {
        cpName = parent.get(TRANSACTIONS.COUNTERPARTY_NAME);
        credId = parent.get(TRANSACTIONS.CREDITOR_ID);
        iban = parent.get(TRANSACTIONS.COUNTERPARTY_IBAN);
        mndt = a.mandateId() != null ? a.mandateId() : parent.get(TRANSACTIONS.MANDATE_ID);
      }

      int inserted =
          db.insertInto(TRANSACTIONS)
              .set(TRANSACTIONS.CONTENT_HASH, childHash)
              .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
              .set(TRANSACTIONS.IMPORT_ID, (Long) null)
              .set(TRANSACTIONS.ACCOUNT_ID, parent.get(TRANSACTIONS.ACCOUNT_ID))
              .set(TRANSACTIONS.BOOKING_DATE, parent.get(TRANSACTIONS.BOOKING_DATE))
              .set(TRANSACTIONS.VALUE_DATE, parent.get(TRANSACTIONS.VALUE_DATE))
              .set(TRANSACTIONS.AMOUNT, a.amount())
              .set(TRANSACTIONS.CURRENCY, parent.get(TRANSACTIONS.CURRENCY))
              .set(TRANSACTIONS.DIRECTION, parent.get(TRANSACTIONS.DIRECTION))
              .set(TRANSACTIONS.BOOKING_STATUS, parent.get(TRANSACTIONS.BOOKING_STATUS))
              .set(TRANSACTIONS.BOOKING_TEXT, parent.get(TRANSACTIONS.BOOKING_TEXT))
              .set(TRANSACTIONS.REMITTANCE_INFO, a.remittanceInfo())
              .set(TRANSACTIONS.GVC, parent.get(TRANSACTIONS.GVC))
              .set(TRANSACTIONS.GVC_EXTENSION, parent.get(TRANSACTIONS.GVC_EXTENSION))
              .set(TRANSACTIONS.PURPOSE_CODE, parent.get(TRANSACTIONS.PURPOSE_CODE))
              .set(TRANSACTIONS.COUNTERPARTY_NAME, cpName)
              .set(
                  TRANSACTIONS.COUNTERPARTY_ULTIMATE_NAME,
                  parent.get(TRANSACTIONS.COUNTERPARTY_ULTIMATE_NAME))
              .set(TRANSACTIONS.COUNTERPARTY_IBAN, iban)
              .set(TRANSACTIONS.COUNTERPARTY_BIC, parent.get(TRANSACTIONS.COUNTERPARTY_BIC))
              .set(TRANSACTIONS.CREDITOR_ID, credId)
              .set(TRANSACTIONS.MANDATE_ID, mndt)
              .set(TRANSACTIONS.END_TO_END_ID, parent.get(TRANSACTIONS.END_TO_END_ID))
              .set(TRANSACTIONS.SUBSEMBLY_ID, parent.get(TRANSACTIONS.SUBSEMBLY_ID))
              .set(TRANSACTIONS.RAW, parent.get(TRANSACTIONS.RAW))
              .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parent.get(TRANSACTIONS.CONTENT_HASH))
              .set(
                  TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX,
                  parent.get(TRANSACTIONS.OCCURRENCE_INDEX))
              .execute();
      created += inserted;
    }

    return new SplitTransactionAck(
        false, created, createdCpIds, "created " + created + " child allocation(s)");
  }

  private String syntheticSplitHash(String parentHash, int partIndex) {
    String input = parentHash + "|" + partIndex + "|split-part";
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private long ensureCounterpartyByDisplayName(String displayName) {
    String normValue = upperNormalize(displayName);
    String dispName = trimNormalize(displayName);

    Long existing =
        db.select(COUNTERPARTIES.ID)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
            .and(COUNTERPARTIES.IDENTITY_VALUE.eq(normValue))
            .fetchOne(COUNTERPARTIES.ID);
    if (existing != null) {
      return existing;
    }

    long id =
        db.insertInto(COUNTERPARTIES)
            .set(COUNTERPARTIES.IDENTITY_TYPE, "name")
            .set(COUNTERPARTIES.IDENTITY_VALUE, normValue)
            .set(COUNTERPARTIES.DISPLAY_NAME, dispName)
            .set(COUNTERPARTIES.STATUS, "open")
            .returning(COUNTERPARTIES.ID)
            .fetchOne()
            .get(COUNTERPARTIES.ID);

    if (displayName.equalsIgnoreCase(BARGELD_DISPLAY_NAME)) {
      // auto nature as per spec; use "auto" source consistent with classify
      db.insertInto(COUNTERPARTY_TAGS)
          .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, id)
          .set(COUNTERPARTY_TAGS.DIMENSION, "nature")
          .set(COUNTERPARTY_TAGS.VALUE, "umbuchung")
          .set(COUNTERPARTY_TAGS.SOURCE, "auto")
          .execute();
    }
    return id;
  }

  private String upperNormalize(String s) {
    if (s == null) return "";
    String n = Normalizer.normalize(s, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
    return n.toUpperCase(Locale.ROOT);
  }

  private String trimNormalize(String s) {
    if (s == null) return "";
    return Normalizer.normalize(s, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
  }
}

// --- supporting records for split_transaction (package-private, same package as other DTOs) ---

record TxReference(String contentHash, int occurrenceIndex) {}

record Allocation(Long counterpartyId, String displayName, String mandateId, BigDecimal amount, String remittanceInfo) {}

record SplitTransactionAck(boolean unsplitPerformed, int allocationsCreated, List<Long> createdCounterpartyIds, String message) {}
