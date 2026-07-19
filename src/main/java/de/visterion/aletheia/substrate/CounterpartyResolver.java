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
 * present in {@code transactions} and synchronizes each display name with the most-frequent
 * normalized name over the counterparty's bookings (alias-aware; tie &rarr; most recent).
 *
 * <p>Only raw/root rows are considered (TP2): {@code split_parent_content_hash IS NULL}. Split
 * children are ignored to avoid creating duplicate counterparties (e.g. "Bargeld" must only be
 * created by the split tool).
 *
 * <p>Identity priority, never merged across types (1&amp;1/Telekom/Deutsche Glasfaser stay
 * distinct): attributed merchant name (PayPal transparency, #29) &gt; {@code creditor_id} &gt;
 * {@code counterparty_iban} &gt; normalized usable {@code counterparty_name} (NFC, trim, collapse
 * whitespace, upper-cased for the identity key only). Transactions with none of the three are
 * unresolved (cash withdrawals/fees) and skipped.
 *
 * <p>{@code @Order(3)} runs this after {@link de.visterion.aletheia.ingest.IngestRunner}
 * ({@code @Order(1)}) and {@link PayPalAttributionResolver} ({@code @Order(2)}) -- Spring does
 * not order {@link ApplicationRunner} beans without explicit {@code @Order}. The insert is
 * idempotent ({@code ON CONFLICT DO NOTHING}, bootstrapping a display name only for brand-new
 * rows); the most-frequent normalized name over the counterparty's bookings (alias-aware; tie
 * &rarr; most recent) is the sole authority for subsequent updates.
 */
@Component
@Order(3)
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
              normalized_display
              ORDER BY booking_date DESC, counterparty_name ASC,
                       content_hash ASC, occurrence_index ASC
          ) FILTER (WHERE normalized_display <> ''))[1] AS display_name
      FROM (
          SELECT
              CASE
                  WHEN attributed_name IS NOT NULL THEN 'name'
                  WHEN creditor_id IS NOT NULL THEN 'creditor_id'
                  WHEN counterparty_iban IS NOT NULL THEN 'iban'
                  WHEN normalized_name <> '' THEN 'name'
              END AS identity_type,
              CASE
                  WHEN attributed_name IS NOT NULL THEN upper(normalized_attributed)
                  WHEN creditor_id IS NOT NULL THEN creditor_id
                  WHEN counterparty_iban IS NOT NULL THEN counterparty_iban
                  WHEN normalized_name <> '' THEN upper(normalized_name)
              END AS identity_value,
              counterparty_name,
              normalized_display,
              booking_date,
              content_hash,
              occurrence_index
          FROM (
              SELECT
                  creditor_id,
                  counterparty_iban,
                  counterparty_name,
                  attributed_name,
                  trim(regexp_replace(
                      normalize(counterparty_name, NFC), '\\s+', ' ', 'g'
                  )) AS normalized_name,
                  trim(regexp_replace(
                      normalize(attributed_name, NFC), '\\s+', ' ', 'g'
                  )) AS normalized_attributed,
                  trim(regexp_replace(
                      normalize(COALESCE(attributed_name, counterparty_name), NFC),
                      '\\s+', ' ', 'g'
                  )) AS normalized_display,
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
        AND NOT EXISTS (
            SELECT 1 FROM counterparty_alias a
            WHERE a.identity_type = identified.identity_type
              AND a.identity_value = identified.identity_value
        )
      GROUP BY identity_type, identity_value
      ON CONFLICT (identity_type, identity_value) DO NOTHING
      """;

  // Intentionally duplicates UPSERT_COUNTERPARTIES's identity CASE ladder verbatim (spec-mandated,
  // Spec B/P2): the refresh must resolve identities with the resolver's own ladder, not the V15
  // evidence-view ladder, which differs on whitespace-only names. Keep both in sync by hand.
  // language=SQL
  private static final String REFRESH_DISPLAY_NAMES =
      """
      UPDATE counterparties c
      SET display_name = ranked.display_name
      FROM (
          SELECT effective_cp,
                 (array_agg(display_name ORDER BY cnt DESC, last_booking DESC, display_name ASC))[1]
                   AS display_name
          FROM (
              SELECT
                  COALESCE(al.canonical_counterparty_id, own.id) AS effective_cp,
                  identified.normalized_display                  AS display_name,
                  count(*)                                       AS cnt,
                  max(identified.booking_date)                   AS last_booking
              FROM (
                  SELECT
                      CASE
                          WHEN attributed_name IS NOT NULL THEN 'name'
                          WHEN creditor_id IS NOT NULL THEN 'creditor_id'
                          WHEN counterparty_iban IS NOT NULL THEN 'iban'
                          WHEN normalized_name <> '' THEN 'name'
                      END AS identity_type,
                      CASE
                          WHEN attributed_name IS NOT NULL THEN upper(normalized_attributed)
                          WHEN creditor_id IS NOT NULL THEN creditor_id
                          WHEN counterparty_iban IS NOT NULL THEN counterparty_iban
                          WHEN normalized_name <> '' THEN upper(normalized_name)
                      END AS identity_value,
                      normalized_display,
                      booking_date
                  FROM (
                      SELECT
                          creditor_id,
                          counterparty_iban,
                          attributed_name,
                          trim(regexp_replace(
                              normalize(counterparty_name, NFC), '\\s+', ' ', 'g'
                          )) AS normalized_name,
                          trim(regexp_replace(
                              normalize(attributed_name, NFC), '\\s+', ' ', 'g'
                          )) AS normalized_attributed,
                          trim(regexp_replace(
                              normalize(COALESCE(attributed_name, counterparty_name), NFC),
                              '\\s+', ' ', 'g'
                          )) AS normalized_display,
                          booking_date
                      FROM transactions
                  """
          + " WHERE "
          + TransactionLayerSql.RAW_ROOT_PREDICATE
          + "\n"
          + """
                  ) normalized
              ) identified
              LEFT JOIN counterparty_alias al
                  ON al.identity_type = identified.identity_type
                 AND al.identity_value = identified.identity_value
              LEFT JOIN counterparties own
                  ON own.identity_type = identified.identity_type
                 AND own.identity_value = identified.identity_value
              WHERE identified.identity_type IS NOT NULL
                AND identified.normalized_display <> ''
              GROUP BY effective_cp, identified.normalized_display
          ) per_name
          WHERE effective_cp IS NOT NULL
          GROUP BY effective_cp
      ) ranked
      WHERE c.id = ranked.effective_cp
        AND c.merged_into IS NULL
        AND c.display_name IS DISTINCT FROM ranked.display_name
      """;

  private final DSLContext db;

  public CounterpartyResolver(DSLContext db) {
    this.db = db;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      resolve();
    } catch (RuntimeException e) {
      log.warn(
          "Startup counterparty resolution failed; will retry on next ingest/restart: {}",
          e.toString());
    }
  }

  /** Idempotent counterparty upsert + alias-aware most-frequent display-name refresh. */
  public void resolve() {
    int inserted = db.execute(UPSERT_COUNTERPARTIES);
    int refreshed = db.execute(REFRESH_DISPLAY_NAMES);
    log.info(
        "Counterparty resolution inserted {} counterparties, refreshed {} display names",
        inserted,
        refreshed);
  }
}
