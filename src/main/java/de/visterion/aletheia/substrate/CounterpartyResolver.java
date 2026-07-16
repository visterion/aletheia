package de.visterion.aletheia.substrate;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup (after ingest, spec §3), upserts {@code counterparties} from the distinct identities
 * present in {@code transactions} and synchronizes each display name with the latest usable raw
 * booking name.
 *
 * <p>Only raw/root rows are considered (TP2): {@code split_parent_content_hash IS NULL}. Split
 * children are ignored to avoid creating duplicate counterparties (e.g. "Bargeld" must only be
 * created by the split tool).
 *
 * <p>Identity priority, never merged across types (1&amp;1/Telekom/Deutsche Glasfaser stay
 * distinct): {@code creditor_id} &gt; {@code counterparty_iban} &gt; normalized usable {@code
 * counterparty_name} (NFC, trim, collapse whitespace, upper-cased for the identity key only).
 * Transactions with none of the three are unresolved (cash withdrawals/fees) and skipped.
 *
 * <p>{@code @Order(2)} runs this after {@link de.visterion.aletheia.ingest.IngestRunner}
 * ({@code @Order(1)}) -- Spring does not order {@link ApplicationRunner} beans without explicit
 * {@code @Order}. The upsert is idempotent: conflicts update only {@code display_name}, and only
 * when the latest derived value is distinct from the stored value.
 */
@Component
@Order(2)
public class CounterpartyResolver implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CounterpartyResolver.class);

  // language=SQL
  private static final String UPSERT_COUNTERPARTIES =
      """
      INSERT INTO counterparties (identity_type, identity_value, display_name)
      SELECT
          identity_type,
          identity_value,
          (array_agg(
              normalized_name
              ORDER BY booking_date DESC, counterparty_name ASC,
                       content_hash ASC, occurrence_index ASC
          ) FILTER (WHERE normalized_name <> ''))[1] AS display_name
      FROM (
          SELECT
              CASE
                  WHEN creditor_id IS NOT NULL THEN 'creditor_id'
                  WHEN counterparty_iban IS NOT NULL THEN 'iban'
                  WHEN normalized_name <> '' THEN 'name'
              END AS identity_type,
              CASE
                  WHEN creditor_id IS NOT NULL THEN creditor_id
                  WHEN counterparty_iban IS NOT NULL THEN counterparty_iban
                  WHEN normalized_name <> '' THEN upper(normalized_name)
              END AS identity_value,
              counterparty_name,
              normalized_name,
              booking_date,
              content_hash,
              occurrence_index
          FROM (
              SELECT
                  creditor_id,
                  counterparty_iban,
                  counterparty_name,
                  trim(regexp_replace(
                      normalize(counterparty_name, NFC), '\\s+', ' ', 'g'
                  )) AS normalized_name,
                  booking_date,
                  content_hash,
                  occurrence_index
              FROM transactions
          """
          + " WHERE "
          + TransactionLayerSql.RAW_ROOT_PREDICATE
          + "\n"
          + """
          ) normalized
      ) identified
      WHERE identity_type IS NOT NULL
      GROUP BY identity_type, identity_value
      ON CONFLICT (identity_type, identity_value) DO UPDATE
      SET display_name = EXCLUDED.display_name
      WHERE EXCLUDED.display_name IS NOT NULL
        AND counterparties.display_name IS DISTINCT FROM EXCLUDED.display_name
      """;

  private final DSLContext db;

  public CounterpartyResolver(DSLContext db) {
    this.db = db;
  }

  @Override
  public void run(ApplicationArguments args) {
    resolve();
  }

  /** Idempotent counterparty upsert. Callable at startup and after each ingest. */
  public void resolve() {
    int affected = db.execute(UPSERT_COUNTERPARTIES);
    log.info("Counterparty resolution inserted or refreshed {} counterparties", affected);
  }
}
