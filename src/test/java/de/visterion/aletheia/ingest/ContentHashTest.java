package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContentHashTest {

  private static String h(
      String acct, String ccy, String dt, String amt, String dir,
      String nm, String inf, String mndt, String e2e) {
    return ContentHash.hashHex(acct, ccy, dt, amt, dir, nm, inf, mndt, e2e);
  }

  @Test
  void amountIsCanonicalizedToTwoDecimals() {
    assertThat(ContentHash.normalizeAmount("943.4600000000000000000000000")).isEqualTo("943.46");
    assertThat(ContentHash.normalizeAmount("780")).isEqualTo("780.00");
    assertThat(ContentHash.normalizeAmount("60.1")).isEqualTo("60.10");
  }

  @Test
  void amountWithMoreThanTwoDecimalsFailsLoud() {
    assertThatThrownBy(() -> ContentHash.normalizeAmount("1.005"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullAndEmptyTextCollapseToSameHash() {
    String a = h("DE1", "EUR", "2026-08-01", "10.00", "DBIT", null, null, null, null);
    String b = h("DE1", "EUR", "2026-08-01", "10.00", "DBIT", "", "", "", "");
    assertThat(a).isEqualTo(b);
  }

  @Test
  void whitespaceIsCollapsedAndTrimmed() {
    String a = h("DE1", "EUR", "2026-08-01", "10.00", "DBIT", "  ACME   GMBH ", "x", null, null);
    String b = h("DE1", "EUR", "2026-08-01", "10.00", "DBIT", "ACME GMBH", "x", null, null);
    assertThat(a).isEqualTo(b);
  }

  @Test
  void differentAccountKeyHashesDifferently() {
    String a = h("DE1", "EUR", "2026-08-01", "49.99", "DBIT", "ACME", "Beitrag", null, null);
    String b = h("DE2", "EUR", "2026-08-01", "49.99", "DBIT", "ACME", "Beitrag", null, null);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void differentCurrencyHashesDifferently() {
    String a = h("DE1", "EUR", "2026-08-01", "100.00", "DBIT", "ACME", "x", null, null);
    String b = h("DE1", "USD", "2026-08-01", "100.00", "DBIT", "ACME", "x", null, null);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void differentMandateHashesDifferently() {
    String a = h("DE1", "EUR", "2026-08-01", "49.99", "DBIT", "INSURER", "Beitrag", "MND-A", null);
    String b = h("DE1", "EUR", "2026-08-01", "49.99", "DBIT", "INSURER", "Beitrag", "MND-B", null);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void delimiterInFieldDoesNotCollideWithFieldBoundary() {
    // "a|b" in one field must not equal "a" and "b" in adjacent fields.
    String a = h("DE1", "EUR", "2026-08-01", "10.00", "DBIT", "a|b", "", null, null);
    String b = h("DE1", "EUR", "2026-08-01", "10.00", "DBIT", "a", "b", null, null);
    assertThat(a).isNotEqualTo(b);
  }
}
