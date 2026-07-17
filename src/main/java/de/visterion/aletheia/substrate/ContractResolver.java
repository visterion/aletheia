package de.visterion.aletheia.substrate;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup (after {@link CounterpartyResolver}, spec TP1 §Detection), derives the contract
 * layer from {@code transactions}: a {@code mandate_id} booked in >= 2 distinct calendar
 * months under a {@code creditor_id}-identity counterparty is a contract.
 *
 * <p>Attributed rows (PayPal transparency, feature #29) take a separate path: a transaction
 * with {@code attributed_name} set materializes a per-merchant contract on the name-identity
 * counterparty with the synthetic mandate {@code 'attributed'}, once the merchant has >= 2
 * distinct calendar months of activity. This deliberately splits a shared PayPal mandate into
 * one contract per underlying merchant instead of a single lumped contract on the PayPal
 * creditor identity, so the creditor path below excludes any row with {@code attributed_name
 * IS NOT NULL}.
 *
 * <p>Only raw/root rows are considered (TP2): {@code split_parent_content_hash IS NULL}. This
 * prevents creating contracts from logical split children (purchase parts must not trigger new
 * contracts via resolver).
 *
 * <p>Idempotent. {@code contracts} holds no measured fields, so its upsert is {@code DO
 * NOTHING} (skipping preserves a confirmed row). {@code recurring} holds the measured series,
 * so its upsert is {@code DO UPDATE} of the measured columns while preserving {@code
 * source}/{@code confidence} (a re-imported premium raise must surface without downgrading a
 * human confirmation). Never invents a NULL-mandate contract row — that is materialized only
 * by a human confirm/link.
 */
@Component
@Order(3)
public class ContractResolver implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ContractResolver.class);

  // language=SQL
  private static final String UPSERT_CONTRACTS =
      """
      INSERT INTO contracts (counterparty_id, mandate_id, source, status)
      SELECT c.id, r.mandate_id, 'auto', 'open'
      FROM (
          SELECT
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN 'name'
                  WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
              END AS identity_type,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.attributed_name, NFC), '\\s+', ' ', 'g')))
                  WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
              END AS identity_value,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN 'attributed'
                  ELSE t.mandate_id
              END AS mandate_id,
              date_trunc('month', t.booking_date) AS month
          FROM transactions t
          WHERE t."""
          + TransactionLayerSql.RAW_ROOT_PREDICATE
          + """

            AND (t.attributed_name IS NOT NULL
                 OR (t.creditor_id IS NOT NULL AND t.mandate_id IS NOT NULL))
      ) r
      JOIN counterparties c
          ON c.identity_type = r.identity_type AND c.identity_value = r.identity_value
      GROUP BY c.id, r.mandate_id
      HAVING count(DISTINCT r.month) >= 2
      ON CONFLICT (counterparty_id, mandate_id) DO NOTHING
      """;

  // language=SQL
  private static final String UPSERT_RECURRING =
      """
      INSERT INTO recurring (counterparty_id, contract_id, cadence, typical_amount,
                             amount_min, amount_max, first_seen, last_seen,
                             occurrence_count, source)
      SELECT
          ct.counterparty_id,
          ct.id,
          'irregular',
          m.typical_amount,
          m.amount_min,
          m.amount_max,
          m.first_seen,
          m.last_seen,
          m.occurrence_count,
          'auto'
      FROM contracts ct
      JOIN counterparties c ON c.id = ct.counterparty_id
      JOIN (
          SELECT
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN 'name'
                  WHEN t.creditor_id IS NOT NULL THEN 'creditor_id'
              END AS identity_type,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(t.attributed_name, NFC), '\\s+', ' ', 'g')))
                  WHEN t.creditor_id IS NOT NULL THEN t.creditor_id
              END AS identity_value,
              CASE
                  WHEN t.attributed_name IS NOT NULL THEN 'attributed'
                  ELSE t.mandate_id
              END AS mandate_id,
              percentile_cont(0.5) WITHIN GROUP (ORDER BY t.amount) AS typical_amount,
              min(t.amount) AS amount_min,
              max(t.amount) AS amount_max,
              min(t.booking_date) AS first_seen,
              max(t.booking_date) AS last_seen,
              count(*) AS occurrence_count
          FROM transactions t
          WHERE t."""
          + TransactionLayerSql.RAW_ROOT_PREDICATE
          + """

            AND (t.attributed_name IS NOT NULL
                 OR (t.creditor_id IS NOT NULL AND t.mandate_id IS NOT NULL))
          GROUP BY 1, 2, 3
      ) m ON m.identity_type = c.identity_type
         AND m.identity_value = c.identity_value
         AND m.mandate_id = ct.mandate_id
      WHERE ct.mandate_id IS NOT NULL
      ON CONFLICT (counterparty_id, contract_id) DO UPDATE SET
          typical_amount   = EXCLUDED.typical_amount,
          amount_min       = EXCLUDED.amount_min,
          amount_max       = EXCLUDED.amount_max,
          first_seen       = EXCLUDED.first_seen,
          last_seen        = EXCLUDED.last_seen,
          occurrence_count = EXCLUDED.occurrence_count,
          source           = recurring.source
      """;

  // language=SQL
  private static final String COUNT_ORPHAN_MANDATES =
      """
      SELECT count(*) FROM (
          SELECT t.mandate_id
          FROM transactions t
          WHERE t.mandate_id IS NOT NULL
            AND (t.creditor_id IS NULL OR t.creditor_id = '')
            AND t.attributed_name IS NULL
            AND t."""
          + TransactionLayerSql.RAW_ROOT_PREDICATE
          + "\n"
          + """
          GROUP BY t.mandate_id
          HAVING count(DISTINCT date_trunc('month', t.booking_date)) >= 2
      ) q
      """;

  private final DSLContext db;

  public ContractResolver(DSLContext db) {
    this.db = db;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      resolve();
    } catch (RuntimeException e) {
      log.warn(
          "Startup contract resolution failed; will retry on next ingest/restart: {}",
          e.toString());
    }
  }

  /** Idempotent contract + recurring upsert. Callable at startup and after each ingest. */
  public void resolve() {
    int contracts = db.execute(UPSERT_CONTRACTS);
    int recurring = db.execute(UPSERT_RECURRING);
    Integer orphans = db.fetchOne(COUNT_ORPHAN_MANDATES).get(0, Integer.class);
    if (orphans != null && orphans > 0) {
      log.warn(
          "{} recurring mandate(s) meet the >=2-month rule but lack a creditor_id "
              + "and produced no contract",
          orphans);
    }
    log.info("Contract resolution upserted {} contracts, {} recurring rows", contracts, recurring);
  }
}
