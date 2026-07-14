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
 * {@code counterparties.status}/{@code reviewed} are exclusively owned by {@link #confirm} and
 * {@link #dismiss} (spec §5, "the workflow").
 */
@Component
public class WriteTools {

  private static final int MAX_BATCH_SIZE = 1000;
  private static final int CONFIRM_REQUIRED_THRESHOLD = 200;

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
      @ToolParam(description = "must be true to run a batch of 200 or more") boolean confirm) {
    List<Long> ids = resolveTargetIds(counterpartyIds, where);
    enforceBatchCaps(ids, confirm);

    if (tags == null || tags.isEmpty()) {
      return new BatchWriteAck(ids.size(), List.of());
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
      return counterpartyIds;
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
          "Record/replace the recurring series for a counterparty (upsert on counterparty_id)."
              + " Never sets counterparties.reviewed or status.")
  public WriteAck markRecurring(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(description = "recurrence interval") Cadence cadence,
      @ToolParam(description = "the representative amount per occurrence") BigDecimal typicalAmount,
      @ToolParam(description = "smallest observed amount, optional", required = false)
          BigDecimal amountMin,
      @ToolParam(description = "largest observed amount, optional", required = false)
          BigDecimal amountMax,
      @ToolParam(description = "provenance of this classification") TagSource source,
      @ToolParam(description = "0..1, optional", required = false) BigDecimal confidence) {
    requireExistingCounterparty(counterpartyId);

    BigDecimal oldTypicalAmount =
        db.select(RECURRING.TYPICAL_AMOUNT)
            .from(RECURRING)
            .where(RECURRING.COUNTERPARTY_ID.eq(counterpartyId))
            .fetchOne(RECURRING.TYPICAL_AMOUNT);

    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
        .set(RECURRING.CADENCE, cadence.name())
        .set(RECURRING.TYPICAL_AMOUNT, typicalAmount)
        .set(RECURRING.AMOUNT_MIN, amountMin)
        .set(RECURRING.AMOUNT_MAX, amountMax)
        .set(RECURRING.SOURCE, source.name())
        .set(RECURRING.CONFIDENCE, confidence)
        .onConflict(RECURRING.COUNTERPARTY_ID)
        .doUpdate()
        .set(RECURRING.CADENCE, cadence.name())
        .set(RECURRING.TYPICAL_AMOUNT, typicalAmount)
        .set(RECURRING.AMOUNT_MIN, amountMin)
        .set(RECURRING.AMOUNT_MAX, amountMax)
        .set(RECURRING.SOURCE, source.name())
        .set(RECURRING.CONFIDENCE, confidence)
        .execute();

    insertHistory(
        counterpartyId,
        "recurring",
        oldTypicalAmount == null ? null : oldTypicalAmount.toPlainString(),
        typicalAmount == null ? null : typicalAmount.toPlainString(),
        source.name());

    return new WriteAck(counterpartyId, "recurring series set to " + cadence.name());
  }

  @Tool(
      name = "confirm",
      description =
          "The human's 'yes': flip this counterparty's auto tags/recurring to confirmed, set"
              + " reviewed=true and status='confirmed'. Drains the review queue for this"
              + " counterparty.")
  public WriteAck confirm(@ToolParam(description = "counterparties.id") long counterpartyId) {
    String oldStatus = requireExistingCounterparty(counterpartyId);

    db.update(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.SOURCE, "confirmed")
        .where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(counterpartyId))
        .and(COUNTERPARTY_TAGS.SOURCE.eq("auto"))
        .execute();

    db.update(RECURRING)
        .set(RECURRING.SOURCE, "confirmed")
        .where(RECURRING.COUNTERPARTY_ID.eq(counterpartyId))
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

  @Tool(
      name = "link_contract",
      description = "Link this counterparty to a HiveMem contract cell (insert/update contracts).")
  public WriteAck linkContract(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(description = "the HiveMem cell id") String hivememCellId,
      @ToolParam(description = "optional free-text notes", required = false) String notes) {
    requireExistingCounterparty(counterpartyId);

    String oldCellId =
        db.select(CONTRACTS.HIVEMEM_CELL_ID)
            .from(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(counterpartyId))
            .fetchOne(CONTRACTS.HIVEMEM_CELL_ID);

    // Upsert on the uq_contract_counterparty unique key (V6): link/relink is a single
    // statement, so there is no fetchExists-then-insert/update TOCTOU window in which two
    // concurrent calls could each observe "no row" and both insert.
    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
        .set(CONTRACTS.HIVEMEM_CELL_ID, hivememCellId)
        .set(CONTRACTS.NOTES, notes)
        .set(CONTRACTS.STATUS, "linked")
        .set(CONTRACTS.CONFIRMED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
        .onConflict(CONTRACTS.COUNTERPARTY_ID)
        .doUpdate()
        .set(CONTRACTS.HIVEMEM_CELL_ID, hivememCellId)
        .set(CONTRACTS.NOTES, notes)
        .set(CONTRACTS.STATUS, "linked")
        .set(CONTRACTS.CONFIRMED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
        .execute();

    insertHistory(counterpartyId, "contract", oldCellId, hivememCellId, "confirmed");

    return new WriteAck(counterpartyId, "linked to HiveMem cell " + hivememCellId);
  }

  @Tool(
      name = "dismiss",
      description =
          "This counterparty is not an obligation / not recurring: status='dismissed',"
              + " dismissed_reason=reason.")
  public WriteAck dismiss(
      @ToolParam(description = "counterparties.id") long counterpartyId,
      @ToolParam(description = "why this counterparty was dismissed") String reason) {
    String oldStatus = requireExistingCounterparty(counterpartyId);

    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.STATUS, "dismissed")
        .set(COUNTERPARTIES.DISMISSED_REASON, reason)
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .execute();

    insertHistory(counterpartyId, "status", oldStatus, "dismissed", "confirmed");

    return new WriteAck(counterpartyId, "dismissed: " + reason);
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
}
