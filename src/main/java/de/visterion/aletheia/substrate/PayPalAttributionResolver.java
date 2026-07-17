package de.visterion.aletheia.substrate;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Deterministic PayPal transparency stage (#29). For bookings whose {@code creditor_id} is in
 * the configured PayPal allowlist, parses the merchant from {@code remittance_info} (format
 * {@code . <MERCHANT>, Ihr Einkauf bei <MERCHANT>}; the first occurrence before the comma is
 * complete, the second is often truncated) and stores it in {@code attributed_name} /
 * {@code attribution_source='paypal'}. All identity-derivation sites then read that column, so
 * the parse lives only here (single-sourced).
 *
 * <p>Raw/root rows only ({@code split_parent_content_hash IS NULL}) -- synthetic split children
 * are never processed (substrate doctrine). Idempotent: only updates when the value would
 * change; never clobbers a {@code source='manual'} override (forward-compat with #43). If the
 * allowlist is empty (default) the stage is a no-op.
 *
 * <p>{@code @Order(2)} runs after {@code IngestRunner} ({@code @Order(1)}) and before
 * {@code CounterpartyResolver} ({@code @Order(3)}), so the attribution is available when the
 * counterparty upsert derives identities.
 */
@Component
@Order(2)
public class PayPalAttributionResolver implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(PayPalAttributionResolver.class);

  // Single-sourced parse. \. matches ". " before the (complete) first merchant occurrence;
  // (.+?) is non-greedy so the first ", Ihr Einkauf bei" wins; the empty-merchant form
  // ". , Ihr Einkauf bei" fails to match (capture needs >= 1 char). Backslashes doubled for
  // the Java text block (same precedent as CounterpartyResolver's normalize regex).
  // language=SQL
  private static final String ATTRIBUTE_PAYPAL =
      """
      UPDATE transactions t
      SET attributed_name = m.merchant, attribution_source = 'paypal'
      FROM (
          SELECT id,
                 trim(substring(remittance_info from '\\. (.+?), Ihr Einkauf bei')) AS merchant
          FROM transactions
          WHERE creditor_id = ANY(?)
            AND split_parent_content_hash IS NULL
      ) m
      WHERE t.id = m.id
        AND m.merchant IS NOT NULL AND m.merchant <> ''
        AND t.attribution_source IS DISTINCT FROM 'manual'
        AND (t.attributed_name    IS DISTINCT FROM m.merchant
          OR t.attribution_source IS DISTINCT FROM 'paypal')
      """;

  private final DSLContext db;
  private final List<String> creditorIds;

  public PayPalAttributionResolver(DSLContext db, PayPalProperties properties) {
    this.db = db;
    this.creditorIds = properties.creditorIds();
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      resolve();
    } catch (RuntimeException e) {
      log.warn(
          "Startup PayPal attribution failed; will retry on next ingest/restart: {}",
          e.toString());
    }
  }

  /** Idempotent PayPal merchant attribution. Callable at startup and after each ingest. */
  public int resolve() {
    if (creditorIds == null || creditorIds.isEmpty()) {
      log.info("PayPal attribution disabled (no aletheia.paypal.creditor-ids configured)");
      return 0;
    }
    int affected =
        db.execute(ATTRIBUTE_PAYPAL, (Object) DSL.array(creditorIds.toArray(new String[0])));
    log.info("PayPal attribution set the merchant on {} booking(s)", affected);
    return affected;
  }
}
