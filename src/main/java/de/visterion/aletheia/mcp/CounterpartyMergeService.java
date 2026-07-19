package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_ALIAS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.jooq.tables.records.ContractsRecord;
import de.visterion.aletheia.jooq.tables.records.RecurringRecord;
import java.util.LinkedHashSet;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The {@code merge_counterparty} merge core (spec {@code 2026-07-19-counterparty-merge-alias-design.md}
 * §3). Called by {@link WriteTools#mergeCounterparty} from inside a {@code TransactionTemplate}
 * unit, itself inside {@link de.visterion.aletheia.substrate.SubstrateLock} -- this class does no
 * transaction/lock management of its own, it only runs the per-source SQL steps.
 *
 * <p>Per source, in order: (1) alias the source's identity onto the target and flatten any
 * alias/{@code merged_into} chains that pointed at the source; (2) union the source's tags onto
 * the target (confirmed never downgraded) and drop the source's tags; (3) migrate the
 * human-authored obligation layer (confirmed contracts, contracts carrying a confirmed recurring,
 * and every contract-less recurring row -- the only ones a resolver re-run can never recreate)
 * onto the target, matching against the target's <em>live</em> state so a second source folded in
 * the same call collides with what the first source already moved there; (4) drop whatever
 * (necessarily auto) recurring/contracts remain on the source -- the settle re-derives them onto
 * the target; (5) soft-delete the source ({@code merged_into = target}) and log history.
 */
@Component
public class CounterpartyMergeService {

  private final DSLContext db;

  public CounterpartyMergeService(DSLContext db) {
    this.db = db;
  }

  /**
   * Runs the full merge core for {@code targetId} <- {@code sourceIds}. Guards throw {@link
   * IllegalArgumentException}. Returns the number of sources actually folded (a source already
   * folded into {@code targetId} is a no-op and does not count).
   */
  public int mergeCore(long targetId, List<Long> sourceIds, String reason) {
    validateGuards(targetId, sourceIds);
    int merged = 0;
    for (long sourceId : sourceIds) {
      Long currentMergedInto =
          db.select(COUNTERPARTIES.MERGED_INTO)
              .from(COUNTERPARTIES)
              .where(COUNTERPARTIES.ID.eq(sourceId))
              .fetchOne(COUNTERPARTIES.MERGED_INTO);
      if (currentMergedInto != null) {
        if (currentMergedInto == targetId) {
          continue; // idempotent no-op: already folded into this target
        }
        throw new IllegalArgumentException(
            "counterparty "
                + sourceId
                + " has already been merged into a different counterparty ("
                + currentMergedInto
                + ")");
      }
      mergeOneSource(targetId, sourceId, reason);
      merged++;
    }
    return merged;
  }

  private void validateGuards(long targetId, List<Long> sourceIds) {
    if (sourceIds == null || sourceIds.isEmpty()) {
      throw new IllegalArgumentException("sourceIds must be non-empty");
    }
    if (new LinkedHashSet<>(sourceIds).size() != sourceIds.size()) {
      throw new IllegalArgumentException("sourceIds must not contain duplicates");
    }
    if (sourceIds.contains(targetId)) {
      throw new IllegalArgumentException("sourceIds must not contain the target itself");
    }
    Long targetMergedInto =
        db.select(COUNTERPARTIES.MERGED_INTO)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(targetId))
            .fetchOne(COUNTERPARTIES.MERGED_INTO);
    if (targetMergedInto == null
        && !db.fetchExists(
            db.selectOne().from(COUNTERPARTIES).where(COUNTERPARTIES.ID.eq(targetId)))) {
      throw new IllegalArgumentException("no such counterparty (target): " + targetId);
    }
    if (targetMergedInto != null) {
      throw new IllegalArgumentException(
          "target " + targetId + " has itself been merged into " + targetMergedInto);
    }
    for (long sourceId : sourceIds) {
      if (!db.fetchExists(
          db.selectOne().from(COUNTERPARTIES).where(COUNTERPARTIES.ID.eq(sourceId)))) {
        throw new IllegalArgumentException("no such counterparty (source): " + sourceId);
      }
    }
  }

  private void mergeOneSource(long targetId, long sourceId, String reason) {
    foldAliasAndChains(targetId, sourceId, reason);
    foldTags(targetId, sourceId);
    migrateContractlessRecurring(targetId, sourceId);
    migrateContracts(targetId, sourceId);
    dropRemainingAutoLayer(sourceId);
    softDelete(targetId, sourceId, reason);
  }

  // --- step 1: alias + chain flattening ---

  private void foldAliasAndChains(long targetId, long sourceId, String reason) {
    Record2<String, String> identity =
        db.select(COUNTERPARTIES.IDENTITY_TYPE, COUNTERPARTIES.IDENTITY_VALUE)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.ID.eq(sourceId))
            .fetchOne();

    db.insertInto(COUNTERPARTY_ALIAS)
        .set(COUNTERPARTY_ALIAS.IDENTITY_TYPE, identity.value1())
        .set(COUNTERPARTY_ALIAS.IDENTITY_VALUE, identity.value2())
        .set(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID, targetId)
        .set(COUNTERPARTY_ALIAS.REASON, reason)
        .onConflict(COUNTERPARTY_ALIAS.IDENTITY_TYPE, COUNTERPARTY_ALIAS.IDENTITY_VALUE)
        .doUpdate()
        .set(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID, targetId)
        .set(COUNTERPARTY_ALIAS.REASON, reason)
        .execute();

    // Transitive alias chain: anything that already aliased to the source now aliases the target.
    db.update(COUNTERPARTY_ALIAS)
        .set(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID, targetId)
        .where(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID.eq(sourceId))
        .execute();

    // merged_into chain: anything previously folded into the source now points at the target.
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.MERGED_INTO, targetId)
        .where(COUNTERPARTIES.MERGED_INTO.eq(sourceId))
        .execute();
  }

  // --- step 2: tags (pure union, confirmed never downgraded) ---

  // language=SQL
  private static final String FOLD_TAGS_SQL =
      """
      INSERT INTO counterparty_tags (counterparty_id, dimension, value, source, confidence)
      SELECT ?, dimension, value, source, confidence FROM counterparty_tags WHERE counterparty_id = ?
      ON CONFLICT (counterparty_id, dimension, value) DO UPDATE SET
          source = CASE WHEN excluded.source = 'confirmed'
                          OR counterparty_tags.source = 'confirmed' THEN 'confirmed' ELSE 'auto' END,
          confidence = GREATEST(excluded.confidence, counterparty_tags.confidence)
      """;

  private void foldTags(long targetId, long sourceId) {
    db.execute(FOLD_TAGS_SQL, targetId, sourceId);
    db.deleteFrom(COUNTERPARTY_TAGS).where(COUNTERPARTY_TAGS.COUNTERPARTY_ID.eq(sourceId)).execute();
  }

  // --- step 3: human-authored obligation layer ---

  /** All contract-less ({@code contract_id IS NULL}) recurring rows are human-authored. */
  private void migrateContractlessRecurring(long targetId, long sourceId) {
    RecurringRecord sourceRow =
        db.selectFrom(RECURRING)
            .where(RECURRING.COUNTERPARTY_ID.eq(sourceId))
            .and(RECURRING.CONTRACT_ID.isNull())
            .fetchOne();
    if (sourceRow == null) {
      return;
    }
    RecurringRecord targetRow =
        db.selectFrom(RECURRING)
            .where(RECURRING.COUNTERPARTY_ID.eq(targetId))
            .and(RECURRING.CONTRACT_ID.isNull())
            .fetchOne();
    if (targetRow == null) {
      db.update(RECURRING)
          .set(RECURRING.COUNTERPARTY_ID, targetId)
          .where(RECURRING.ID.eq(sourceRow.getId()))
          .execute();
      insertHistory(
          targetId,
          "recurring:contractless",
          null,
          "moved mandate-less series from counterparty " + sourceId,
          "confirmed");
      return;
    }
    resolveRecurringCollision(targetId, sourceId, targetRow, sourceRow, "recurring:contractless");
  }

  /** Contracts that are confirmed, or that carry a confirmed recurring child. */
  private void migrateContracts(long targetId, long sourceId) {
    for (ContractsRecord sourceContract :
        db.selectFrom(CONTRACTS).where(CONTRACTS.COUNTERPARTY_ID.eq(sourceId)).fetch()) {
      boolean humanAuthored =
          "confirmed".equals(sourceContract.getSource())
              || db.fetchExists(
                  db.selectOne()
                      .from(RECURRING)
                      .where(RECURRING.CONTRACT_ID.eq(sourceContract.getId()))
                      .and(RECURRING.SOURCE.eq("confirmed")));
      if (!humanAuthored) {
        continue; // strictly auto -- left for the bulk delete in step 4
      }
      migrateOneContract(targetId, sourceId, sourceContract);
    }
  }

  private void migrateOneContract(long targetId, long sourceId, ContractsRecord sourceContract) {
    var mandateCondition =
        sourceContract.getMandateId() == null
            ? CONTRACTS.MANDATE_ID.isNull()
            : CONTRACTS.MANDATE_ID.eq(sourceContract.getMandateId());
    ContractsRecord targetContract =
        db.selectFrom(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(targetId))
            .and(mandateCondition)
            .fetchOne();
    RecurringRecord sourceRecurring =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(sourceContract.getId())).fetchOne();

    if (targetContract == null) {
      // No collision: the whole contract (and its recurring, if any) moves onto the target as-is.
      db.update(CONTRACTS)
          .set(CONTRACTS.COUNTERPARTY_ID, targetId)
          .where(CONTRACTS.ID.eq(sourceContract.getId()))
          .execute();
      if (sourceRecurring != null) {
        db.update(RECURRING)
            .set(RECURRING.COUNTERPARTY_ID, targetId)
            .where(RECURRING.ID.eq(sourceRecurring.getId()))
            .execute();
      }
      insertHistory(
          targetId,
          "contract:" + sourceContract.getId(),
          null,
          "moved contract (mandate " + sourceContract.getMandateId() + ") from counterparty " + sourceId,
          "confirmed");
      return;
    }

    // Collision: recurring must be resolved BEFORE the source contract is deleted (FK order).
    if (sourceRecurring != null) {
      RecurringRecord targetRecurring =
          db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(targetContract.getId())).fetchOne();
      if (targetRecurring == null) {
        db.update(RECURRING)
            .set(RECURRING.COUNTERPARTY_ID, targetId)
            .set(RECURRING.CONTRACT_ID, targetContract.getId())
            .where(RECURRING.ID.eq(sourceRecurring.getId()))
            .execute();
      } else {
        resolveRecurringCollision(
            targetId, sourceId, targetRecurring, sourceRecurring, "recurring:contract:" + targetContract.getId());
      }
    }

    boolean sourceConfirmed = "confirmed".equals(sourceContract.getSource());
    boolean targetConfirmed = "confirmed".equals(targetContract.getSource());
    if (sourceConfirmed && !targetConfirmed) {
      db.update(CONTRACTS)
          .set(CONTRACTS.SOURCE, "confirmed")
          .set(CONTRACTS.STATUS, "confirmed")
          .set(CONTRACTS.CONFIRMED_AT, sourceContract.getConfirmedAt())
          .set(CONTRACTS.HIVEMEM_CELL_ID, sourceContract.getHivememCellId())
          .set(CONTRACTS.NOTES, sourceContract.getNotes())
          .where(CONTRACTS.ID.eq(targetContract.getId()))
          .execute();
      insertHistory(
          targetId,
          "contract:" + targetContract.getId(),
          targetContract.getStatus(),
          "confirmed (upgraded from merged counterparty " + sourceId + ")",
          "confirmed");
    } else {
      insertHistory(
          targetId,
          "contract:" + targetContract.getId(),
          null,
          "dropped colliding contract " + sourceContract.getId() + " from merged counterparty " + sourceId,
          "confirmed");
    }
    db.deleteFrom(CONTRACTS).where(CONTRACTS.ID.eq(sourceContract.getId())).execute();
  }

  /**
   * Resolves a recurring-row collision: the source's confirmed series upgrades an unconfirmed
   * target row in place (human cadence/amount preserved); any other combination keeps the
   * target's row and drops the source's. Either way the source row is deleted by the caller-owned
   * transaction (FK-safe: this never touches {@code contracts}).
   */
  private void resolveRecurringCollision(
      long targetId,
      long sourceId,
      RecurringRecord targetRow,
      RecurringRecord sourceRow,
      String historyField) {
    boolean sourceConfirmed = "confirmed".equals(sourceRow.getSource());
    boolean targetConfirmed = "confirmed".equals(targetRow.getSource());
    if (sourceConfirmed && !targetConfirmed) {
      db.update(RECURRING)
          .set(RECURRING.SOURCE, "confirmed")
          .set(RECURRING.CADENCE, sourceRow.getCadence())
          .set(RECURRING.TYPICAL_AMOUNT, sourceRow.getTypicalAmount())
          .set(RECURRING.AMOUNT_MIN, sourceRow.getAmountMin())
          .set(RECURRING.AMOUNT_MAX, sourceRow.getAmountMax())
          .set(RECURRING.CONFIDENCE, sourceRow.getConfidence())
          .where(RECURRING.ID.eq(targetRow.getId()))
          .execute();
      insertHistory(
          targetId,
          historyField,
          targetRow.getTypicalAmount() == null ? null : targetRow.getTypicalAmount().toPlainString(),
          "confirmed (upgraded from merged counterparty " + sourceId + ")",
          "confirmed");
    } else {
      insertHistory(
          targetId,
          historyField,
          null,
          "dropped colliding recurring series from merged counterparty " + sourceId,
          "confirmed");
    }
    db.deleteFrom(RECURRING).where(RECURRING.ID.eq(sourceRow.getId())).execute();
  }

  // --- step 4: drop whatever (necessarily auto) obligation layer remains ---

  private void dropRemainingAutoLayer(long sourceId) {
    db.deleteFrom(RECURRING).where(RECURRING.COUNTERPARTY_ID.eq(sourceId)).execute();
    db.deleteFrom(CONTRACTS).where(CONTRACTS.COUNTERPARTY_ID.eq(sourceId)).execute();
  }

  // --- step 5: soft-delete + history ---

  private void softDelete(long targetId, long sourceId, String reason) {
    db.update(COUNTERPARTIES)
        .set(COUNTERPARTIES.MERGED_INTO, targetId)
        .where(COUNTERPARTIES.ID.eq(sourceId))
        .execute();
    insertHistory(sourceId, "merged_into", null, String.valueOf(targetId), "confirmed");
    insertHistory(
        targetId, "merge", null, "merged counterparty " + sourceId + ": " + reason, "confirmed");
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

  /** Mirrors {@link WriteTools}'s actor resolution (same request-attribute convention). */
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
