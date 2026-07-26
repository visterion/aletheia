package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;

import java.math.BigDecimal;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

/**
 * The single place that writes a TP2 logical-split child row.
 *
 * <p>Two callers derive children today: the human-driven {@code split_transaction} tool ({@code
 * TransactionSplitService}) and the deterministic {@code ProductSplitResolver}. Every column that
 * does not vary per allocation is inherited from the parent row here rather than at the call site,
 * so the two cannot drift apart. This is deliberate: the normalization slice found the same SQL
 * expression hand-copied 20 times across this codebase, which is exactly how a formula silently
 * fragments data. One writer, two callers.
 *
 * <p>The writer does no validation, no normalization and no transaction management -- callers own
 * the amount/sum guards, the child key ({@code syntheticSplitHash(parentHash, i)}) and the
 * enclosing transaction plus {@link SubstrateLock}.
 */
@Component
public class SplitChildWriter {

  /**
   * The per-allocation part of a child row. Everything not listed here is inherited from the parent.
   *
   * @param amount positive child amount
   * @param remittanceInfo child remittance info (the matched position substring, for the resolver)
   * @param counterpartyName raw booking name to store on the child
   * @param creditorId creditor id, or null for name/IBAN-based attribution
   * @param iban counterparty IBAN, or null
   * @param mandateId mandate id, or null
   * @param product normalized product name, or null when no product rule applies (the tool never
   *     sets it)
   * @param productPolicyNo policy number captured alongside the product, or null
   */
  public record ChildValues(
      BigDecimal amount,
      String remittanceInfo,
      String counterpartyName,
      String creditorId,
      String iban,
      String mandateId,
      String product,
      String productPolicyNo) {}

  /**
   * Inserts one child row under {@code parent}.
   *
   * @param db the context to write through (the caller's, so the write joins its transaction)
   * @param parent the raw parent row, fetched from {@code TRANSACTIONS}
   * @param childHash the child's {@code content_hash}; children always use {@code
   *     occurrence_index = 0} and carry no {@code import_id}
   * @return the number of inserted rows
   */
  public int writeChild(DSLContext db, Record parent, String childHash, ChildValues values) {
    return db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, childHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.ACCOUNT_ID, parent.get(TRANSACTIONS.ACCOUNT_ID))
        .set(TRANSACTIONS.BOOKING_DATE, parent.get(TRANSACTIONS.BOOKING_DATE))
        .set(TRANSACTIONS.VALUE_DATE, parent.get(TRANSACTIONS.VALUE_DATE))
        .set(TRANSACTIONS.AMOUNT, values.amount())
        .set(TRANSACTIONS.CURRENCY, parent.get(TRANSACTIONS.CURRENCY))
        .set(TRANSACTIONS.DIRECTION, parent.get(TRANSACTIONS.DIRECTION))
        .set(TRANSACTIONS.BOOKING_STATUS, parent.get(TRANSACTIONS.BOOKING_STATUS))
        .set(TRANSACTIONS.BOOKING_TEXT, parent.get(TRANSACTIONS.BOOKING_TEXT))
        .set(TRANSACTIONS.REMITTANCE_INFO, values.remittanceInfo())
        .set(TRANSACTIONS.GVC, parent.get(TRANSACTIONS.GVC))
        .set(TRANSACTIONS.GVC_EXTENSION, parent.get(TRANSACTIONS.GVC_EXTENSION))
        .set(TRANSACTIONS.PURPOSE_CODE, parent.get(TRANSACTIONS.PURPOSE_CODE))
        .set(TRANSACTIONS.COUNTERPARTY_NAME, values.counterpartyName())
        .set(
            TRANSACTIONS.COUNTERPARTY_ULTIMATE_NAME,
            parent.get(TRANSACTIONS.COUNTERPARTY_ULTIMATE_NAME))
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, values.iban())
        .set(TRANSACTIONS.COUNTERPARTY_BIC, parent.get(TRANSACTIONS.COUNTERPARTY_BIC))
        .set(TRANSACTIONS.CREDITOR_ID, values.creditorId())
        .set(TRANSACTIONS.MANDATE_ID, values.mandateId())
        .set(TRANSACTIONS.END_TO_END_ID, parent.get(TRANSACTIONS.END_TO_END_ID))
        .set(TRANSACTIONS.SUBSEMBLY_ID, parent.get(TRANSACTIONS.SUBSEMBLY_ID))
        .set(TRANSACTIONS.RAW, parent.get(TRANSACTIONS.RAW))
        .set(TRANSACTIONS.PRODUCT, values.product())
        .set(TRANSACTIONS.PRODUCT_POLICY_NO, values.productPolicyNo())
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parent.get(TRANSACTIONS.CONTENT_HASH))
        .set(
            TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX,
            parent.get(TRANSACTIONS.OCCURRENCE_INDEX))
        .execute();
  }
}
