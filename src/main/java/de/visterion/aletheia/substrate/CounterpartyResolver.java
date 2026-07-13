package de.visterion.aletheia.substrate;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup (after ingest, spec §3), upserts {@code counterparties} from the distinct
 * identities present in {@code transactions}.
 *
 * <p>Identity priority, never merged across types (1&amp;1/Telekom/Deutsche Glasfaser stay
 * distinct): {@code creditor_id} &gt; {@code counterparty_iban} &gt; normalized {@code
 * counterparty_name} (NFC, trim, collapse whitespace, upper-cased for the identity key only).
 * Transactions with none of the three are unresolved (cash withdrawals/fees) and skipped.
 *
 * <p>{@code @Order(2)} runs this after {@link de.visterion.aletheia.ingest.IngestRunner}
 * ({@code @Order(1)}) — Spring does not order {@link ApplicationRunner} beans without explicit
 * {@code @Order} (adversarial review M5). Idempotent: {@code ON CONFLICT DO NOTHING} on {@code
 * (identity_type, identity_value)}; a no-op against empty {@code transactions}.
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
          (array_agg(counterparty_name ORDER BY booking_date)
              FILTER (WHERE counterparty_name IS NOT NULL))[1] AS display_name
      FROM (
          SELECT
              CASE
                  WHEN creditor_id IS NOT NULL THEN 'creditor_id'
                  WHEN counterparty_iban IS NOT NULL THEN 'iban'
                  WHEN counterparty_name IS NOT NULL THEN 'name'
              END AS identity_type,
              CASE
                  WHEN creditor_id IS NOT NULL THEN creditor_id
                  WHEN counterparty_iban IS NOT NULL THEN counterparty_iban
                  WHEN counterparty_name IS NOT NULL THEN
                      upper(trim(regexp_replace(normalize(counterparty_name, NFC), '\\s+', ' ', 'g')))
              END AS identity_value,
              counterparty_name,
              booking_date
          FROM transactions
      ) identified
      WHERE identity_type IS NOT NULL
      GROUP BY identity_type, identity_value
      ON CONFLICT (identity_type, identity_value) DO NOTHING
      """;

  private final DSLContext db;

  public CounterpartyResolver(DSLContext db) {
    this.db = db;
  }

  @Override
  public void run(ApplicationArguments args) {
    int inserted = db.execute(UPSERT_COUNTERPARTIES);
    log.info("Counterparty resolution upserted {} new counterparties", inserted);
  }
}
