package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_ALIAS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;

import de.visterion.aletheia.substrate.NameNormalization;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core logic for {@code split_transaction}: replace-semantics child allocations under a raw parent
 * transaction, including on-demand name-based counterparty creation (Bargeld → nature=umbuchung).
 */
@Service
public class TransactionSplitService {

  public static final String BARGELD_DISPLAY_NAME = "Bargeld";
  public static final String BARGELD_NATURE_VALUE = "umbuchung";

  private final DSLContext db;

  public TransactionSplitService(DSLContext db) {
    this.db = db;
  }

  @Transactional
  public SplitTransactionAck splitTransaction(
      TxReference tx, List<Allocation> allocations, Boolean unsplit) {
    if (tx == null || tx.contentHash() == null) {
      throw new IllegalArgumentException("tx reference with contentHash is required");
    }
    int occ = tx.occurrenceIndex();

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
      String cpName;
      String credId;
      String iban;
      String mndt;

      if (cpId != null) {
        var cp =
            db.select(
                    COUNTERPARTIES.IDENTITY_TYPE,
                    COUNTERPARTIES.IDENTITY_VALUE,
                    COUNTERPARTIES.DISPLAY_NAME,
                    COUNTERPARTIES.MERGED_INTO)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.ID.eq(cpId))
                .fetchOne();
        if (cp == null) {
          throw new IllegalArgumentException("no such counterparty: " + cpId);
        }
        Long mergedInto = cp.get(COUNTERPARTIES.MERGED_INTO);
        if (mergedInto != null) {
          throw new IllegalArgumentException(
              "counterparty " + cpId + " has been merged into " + mergedInto + "; use the canonical id");
        }
        String idType = cp.get(COUNTERPARTIES.IDENTITY_TYPE);
        String idValue = cp.get(COUNTERPARTIES.IDENTITY_VALUE);
        String displayName = cp.get(COUNTERPARTIES.DISPLAY_NAME);
        String parentCreditor = parent.get(TRANSACTIONS.CREDITOR_ID);

        if ("creditor_id".equals(idType)) {
          credId = idValue;
          boolean sameAsParent =
              parentCreditor != null && parentCreditor.equals(idValue);
          cpName =
              sameAsParent
                  ? parent.get(TRANSACTIONS.COUNTERPARTY_NAME)
                  : (displayName != null ? displayName : idValue);
          iban = null;
          mndt =
              sameAsParent
                  ? (a.mandateId() != null ? a.mandateId() : parent.get(TRANSACTIONS.MANDATE_ID))
                  : a.mandateId();
        } else if ("iban".equals(idType)) {
          iban = idValue;
          credId = null;
          cpName = displayName != null ? displayName : idValue;
          mndt = a.mandateId();
        } else {
          // name (pseudo) or other identity: name-based attribution
          boolean bargeld =
              displayName != null && displayName.equalsIgnoreCase(BARGELD_DISPLAY_NAME);
          // No normalization roundtrip here: displayName comes from the COUNTERPARTIES row, which
          // is already a fixpoint of the shared formula, so evaluating it would be a no-op -- and
          // an empty stored display_name is legal, so the empty-guard below would raise an error
          // about an "allocation displayName" the caller never supplied.
          cpName = bargeld ? BARGELD_DISPLAY_NAME : (displayName != null ? displayName : idValue);
          credId = null;
          iban = null;
          mndt = a.mandateId();
        }
      } else if (a.displayName() != null && !a.displayName().isBlank()) {
        // Normalize once per allocation, up front: everything below (the pre-existence identity
        // check, the counterparty ensure, the Bargeld decision and the child's name) consumes this
        // single value, and the empty check runs before this allocation's inserts rather than
        // relying on the rollback to undo an ('name', '') row. (The rollback is still load-bearing
        // for the child-delete above and for earlier iterations' inserts.)
        String dn = a.displayName();
        var norm = NameNormalization.evaluate(db, dn);
        if (norm.isEmpty()) {
          throw new IllegalArgumentException(
              "allocation displayName normalizes to empty: " + codePoints(dn));
        }

        // check pre-existence BEFORE ensure (for createdCpIds ack) -- an alias mapping this
        // identity onto a canonical counterparty counts as "already existing" too, since it
        // resolves to that canonical row rather than inserting a new one (see
        // ensureCounterpartyByDisplayName).
        String normForCheck = norm.identity();
        boolean aliasedBefore =
            db.fetchExists(
                db.selectOne()
                    .from(COUNTERPARTY_ALIAS)
                    .where(COUNTERPARTY_ALIAS.IDENTITY_TYPE.eq("name"))
                    .and(COUNTERPARTY_ALIAS.IDENTITY_VALUE.eq(normForCheck)));
        Long existedBefore =
            db.select(COUNTERPARTIES.ID)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
                .and(COUNTERPARTIES.IDENTITY_VALUE.eq(normForCheck))
                .fetchOne(COUNTERPARTIES.ID);
        long ensured = ensureCounterpartyByDisplayName(norm);
        if (existedBefore == null && !aliasedBefore) {
          createdCpIds.add(ensured);
        }
        cpId = ensured;

        boolean bargeld = norm.display().equalsIgnoreCase(BARGELD_DISPLAY_NAME);
        cpName = bargeld ? BARGELD_DISPLAY_NAME : norm.display();
        credId = null;
        iban = null;
        // name-based: only explicit allocation mandate; never inherit parent mandate
        mndt = a.mandateId();
      } else {
        throw new IllegalArgumentException(
            "allocation requires either counterpartyId or displayName");
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

  /**
   * Renders {@code s} as a space-separated list of code points.
   *
   * <p>Only ever called on a name that normalized to empty, which means it consists solely of
   * whitespace/format characters -- anything visible would have survived. Echoing such a value back
   * verbatim would produce a blank error message and leak raw control characters (NEL, line
   * separators) into the MCP error payload and the logs.
   */
  private static String codePoints(String s) {
    StringBuilder sb = new StringBuilder();
    s.codePoints()
        .forEach(
            cp -> {
              if (!sb.isEmpty()) {
                sb.append(' ');
              }
              sb.append(String.format("U+%04X", cp));
            });
    return sb.toString();
  }

  /**
   * Resolves a name-based counterparty, creating it if neither an alias nor a row exists yet.
   *
   * @param norm the caller's already-normalized allocation name. Taking the normal forms instead
   *     of the raw string keeps this method to a single normalization roundtrip per allocation --
   *     the caller needs {@code identity()} for its own pre-existence check anyway -- and makes it
   *     structurally impossible to compare a raw name against {@link #BARGELD_DISPLAY_NAME} here.
   */
  private long ensureCounterpartyByDisplayName(NameNormalization.Normalized norm) {
    String normValue = norm.identity();
    String dispName =
        norm.display().equalsIgnoreCase(BARGELD_DISPLAY_NAME)
            ? BARGELD_DISPLAY_NAME
            : norm.display();

    // Alias routing (sub-project A/P1 counterparty merge, Task 4): a name identity that has been
    // folded onto a canonical counterparty resolves there directly, instead of the folded source
    // row (or a brand-new counterparty if the source row never physically existed).
    Long aliased =
        db.select(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID)
            .from(COUNTERPARTY_ALIAS)
            .where(COUNTERPARTY_ALIAS.IDENTITY_TYPE.eq("name"))
            .and(COUNTERPARTY_ALIAS.IDENTITY_VALUE.eq(normValue))
            .fetchOne(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID);
    if (aliased != null) {
      ensureBargeldNatureIfNeeded(aliased, norm.display());
      return aliased;
    }

    Long existing =
        db.select(COUNTERPARTIES.ID)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
            .and(COUNTERPARTIES.IDENTITY_VALUE.eq(normValue))
            .fetchOne(COUNTERPARTIES.ID);
    if (existing != null) {
      ensureBargeldNatureIfNeeded(existing, norm.display());
      return existing;
    }

    try {
      long id =
          db.insertInto(COUNTERPARTIES)
              .set(COUNTERPARTIES.IDENTITY_TYPE, "name")
              .set(COUNTERPARTIES.IDENTITY_VALUE, normValue)
              .set(COUNTERPARTIES.DISPLAY_NAME, dispName)
              .set(COUNTERPARTIES.STATUS, "open")
              .returning(COUNTERPARTIES.ID)
              .fetchOne()
              .get(COUNTERPARTIES.ID);
      ensureBargeldNatureIfNeeded(id, norm.display());
      return id;
    } catch (DataAccessException ex) {
      // concurrent insert won the race — re-select
      Long raced =
          db.select(COUNTERPARTIES.ID)
              .from(COUNTERPARTIES)
              .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
              .and(COUNTERPARTIES.IDENTITY_VALUE.eq(normValue))
              .fetchOne(COUNTERPARTIES.ID);
      if (raced == null) {
        throw ex;
      }
      ensureBargeldNatureIfNeeded(raced, norm.display());
      return raced;
    }
  }

  /**
   * Tags a Bargeld counterparty with {@code nature=umbuchung}.
   *
   * @param normalizedDisplay the already-normalized display name (a fixpoint of {@link
   *     NameNormalization}). Comparing the raw caller input here would miss names that only
   *     normalize onto the canonical {@code Bargeld} identity (e.g. one carrying an em space), so
   *     the counterparty would be created without the tag that drives the obligations register and
   *     the cashflow role mapping.
   */
  private void ensureBargeldNatureIfNeeded(long cpId, String normalizedDisplay) {
    if (!normalizedDisplay.equalsIgnoreCase(BARGELD_DISPLAY_NAME)) {
      return;
    }
    // PK (counterparty_id, dimension, value) makes onConflictDoNothing safe
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, cpId)
        .set(COUNTERPARTY_TAGS.DIMENSION, "nature")
        .set(COUNTERPARTY_TAGS.VALUE, BARGELD_NATURE_VALUE)
        .set(COUNTERPARTY_TAGS.SOURCE, "auto")
        .onConflictDoNothing()
        .execute();
  }
}
