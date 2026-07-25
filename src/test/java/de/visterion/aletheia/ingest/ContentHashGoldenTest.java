package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Freezes {@link ContentHash}'s normalization dialect as a golden test.
 *
 * <p>{@code ContentHash.normText} has the same NFC+trim+collapse shape as the display-name
 * normalization that is being centralized elsewhere in the codebase. It must never converge with
 * that centralized rule: this hash is the persisted ingest natural key
 * ({@code transactions.content_hash}). Java's {@code \s} only matches ASCII whitespace, while
 * PostgreSQL's whitespace class is wider (it also matches U+0085, U+2000-2006, U+2008-200A,
 * U+2028, U+2029, U+205F and U+3000). If a future change made {@code normText} match the SQL-side
 * dialect, every hash for a booking containing one of those extra whitespace characters would
 * change, and the next re-import of an already-imported export would duplicate that transaction
 * history — violating the project's hard idempotency requirement.
 *
 * <p>If this test goes red, do not update the expected values. Revert whatever changed
 * ContentHash.
 */
class ContentHashGoldenTest {

  private static final String ASCII_GOLDEN_HASH =
      "3f3c2bf77f04697c3ef580b07b8e9c2e7dd98efcbb212afd6b5781ec28880dc7";

  private static final String FAIL_MESSAGE_TEMPLATE =
      "content_hash dialect changed -- this is the persisted ingest natural key. "
          + "Revert the ContentHash change; do NOT update this literal (got %s)";

  private static String h(
      String acct, String ccy, String dt, String amt, String dir,
      String nm, String inf, String mndt, String e2e) {
    return ContentHash.hashHex(acct, ccy, dt, amt, dir, nm, inf, mndt, e2e);
  }

  @Test
  void asciiIsStable() {
    String hash =
        h(
            "DE00SYNTH0000000001",
            "EUR",
            "2026-08-01",
            "49.99",
            "DBIT",
            "Synthetic Merchant",
            "Synthetic remittance info",
            "SYNTH-MANDATE-1",
            "SYNTH-E2E-1");
    assertThat(hash)
        .withFailMessage(FAIL_MESSAGE_TEMPLATE, hash)
        .isEqualTo(ASCII_GOLDEN_HASH);
  }

  @Test
  void asciiWhitespaceCollapses() {
    String hash =
        h(
            "DE00SYNTH0000000001",
            "EUR",
            "2026-08-01",
            "49.99",
            "DBIT",
            "  Synthetic   Merchant  ",
            "Synthetic remittance info",
            "SYNTH-MANDATE-1",
            "SYNTH-E2E-1");
    assertThat(hash)
        .withFailMessage(FAIL_MESSAGE_TEMPLATE, hash)
        .isEqualTo(ASCII_GOLDEN_HASH);
  }

  @Test
  void emSpaceSurvives() {
    String emSpace = String.valueOf((char) 0x2003);
    String hash =
        h(
            "DE00SYNTH0000000001",
            "EUR",
            "2026-08-01",
            "49.99",
            "DBIT",
            "Synthetic" + emSpace + "Merchant",
            "Synthetic remittance info",
            "SYNTH-MANDATE-1",
            "SYNTH-E2E-1");
    assertThat(hash)
        .withFailMessage(FAIL_MESSAGE_TEMPLATE, hash)
        .isEqualTo("bf54004dc3e8eaa2ade274aac741304712da3be81ba74da94f4fe9bc376a23fa");
  }
}
