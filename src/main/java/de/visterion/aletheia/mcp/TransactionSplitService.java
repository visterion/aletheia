package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_ALIAS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;

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
          cpName =
              bargeld
                  ? BARGELD_DISPLAY_NAME
                  : (displayName != null ? trimNormalize(displayName) : idValue);
          credId = null;
          iban = null;
          mndt = a.mandateId();
        }
      } else if (a.displayName() != null && !a.displayName().isBlank()) {
        // check pre-existence BEFORE ensure (for createdCpIds ack) -- an alias mapping this
        // identity onto a canonical counterparty counts as "already existing" too, since it
        // resolves to that canonical row rather than inserting a new one (see
        // ensureCounterpartyByDisplayName).
        String normForCheck = upperNormalize(a.displayName());
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
        long ensured = ensureCounterpartyByDisplayName(a.displayName());
        if (existedBefore == null && !aliasedBefore) {
          createdCpIds.add(ensured);
        }
        cpId = ensured;

        String dn = a.displayName();
        boolean bargeld = dn.equalsIgnoreCase(BARGELD_DISPLAY_NAME);
        cpName = bargeld ? BARGELD_DISPLAY_NAME : trimNormalize(dn);
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

  private long ensureCounterpartyByDisplayName(String displayName) {
    String normValue = upperNormalize(displayName);
    String dispName =
        displayName.equalsIgnoreCase(BARGELD_DISPLAY_NAME)
            ? BARGELD_DISPLAY_NAME
            : trimNormalize(displayName);

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
      ensureBargeldNatureIfNeeded(aliased, displayName);
      return aliased;
    }

    Long existing =
        db.select(COUNTERPARTIES.ID)
            .from(COUNTERPARTIES)
            .where(COUNTERPARTIES.IDENTITY_TYPE.eq("name"))
            .and(COUNTERPARTIES.IDENTITY_VALUE.eq(normValue))
            .fetchOne(COUNTERPARTIES.ID);
    if (existing != null) {
      ensureBargeldNatureIfNeeded(existing, displayName);
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
      ensureBargeldNatureIfNeeded(id, displayName);
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
      ensureBargeldNatureIfNeeded(raced, displayName);
      return raced;
    }
  }

  private void ensureBargeldNatureIfNeeded(long cpId, String displayName) {
    if (!displayName.equalsIgnoreCase(BARGELD_DISPLAY_NAME)) {
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

  private static String upperNormalize(String s) {
    if (s == null) return "";
    String n = Normalizer.normalize(s, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
    return n.toUpperCase(Locale.ROOT);
  }

  private static String trimNormalize(String s) {
    if (s == null) return "";
    return Normalizer.normalize(s, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
  }
}
