package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The parser is pure: raw capture values only, no database and above all no normalization. Every
 * assertion about the normal form of a product name belongs in the resolver's container test,
 * because only PostgreSQL can produce that normal form (see {@link NameNormalization}).
 */
class ProductPositionParserTest {

  // Synthetic illustration only; the real pattern lives in the database, never in the repo.
  // The policy class admits letters and dashes so that "SUB-2" is reachable -- the spec's
  // "[0-9.]+" cannot match its own example.
  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  private final ProductPositionParser parser = new ProductPositionParser();

  @Test
  void parsesTwoPositionsWithAndWithoutPolicyNumber() {
    var r =
        parser.parse(
            PATTERN, "POLICY-1 Health 100,00 Legal SUB-2 50,00", new BigDecimal("150.00"));

    assertThat(r.matched()).isTrue();
    assertThat(r.sumMismatch()).isFalse();
    assertThat(r.positions())
        .extracting(ProductPosition::rawProduct)
        .containsExactly("Health", "Legal");
    assertThat(r.positions().get(0).policyNo()).isNull();
    assertThat(r.positions().get(1).policyNo()).isEqualTo("SUB-2");
    assertThat(r.positions().get(1).amount()).isEqualByComparingTo("50.00");
  }

  @Test
  void keepsTheMatchedSubstringOfEachPosition() {
    var r =
        parser.parse(
            PATTERN, "POLICY-1 Health 100,00 Legal SUB-2 50,00", new BigDecimal("150.00"));

    assertThat(r.positions())
        .extracting(ProductPosition::matchedText)
        .containsExactly("Health 100,00", "Legal SUB-2 50,00");
  }

  @Test
  void thousandsSeparatorParses() {
    var r = parser.parse(PATTERN, "P Health 1.234.567,89", new BigDecimal("1234567.89"));
    assertThat(r.positions().get(0).amount()).isEqualByComparingTo("1234567.89");
  }

  @Test
  void sumMismatchReportsMismatchAndDecidesNothing() {
    var r = parser.parse(PATTERN, "P Health 100,00 Legal 50,00", new BigDecimal("999.00"));

    assertThat(r.matched()).isFalse();
    assertThat(r.sumMismatch()).isTrue();
    assertThat(r.positions()).isEmpty();
  }

  @Test
  void nonMatchingRemittanceYieldsNothingAndIsNotAMismatch() {
    var r = parser.parse(PATTERN, "no positions here", new BigDecimal("10.00"));
    assertThat(r.matched()).isFalse();
    assertThat(r.sumMismatch()).isFalse();
    assertThat(r.positions()).isEmpty();
  }

  @Test
  void differingScaleIsStillAnExactSumMatch() {
    var r = parser.parse(PATTERN, "P Health 100,00", new BigDecimal("100.0"));
    assertThat(r.matched()).isTrue();
    assertThat(r.sumMismatch()).isFalse();
  }

  @Test
  void patternWithoutRequiredGroupsIsRejected() {
    assertThatThrownBy(() -> parser.parse("(?<product>\\p{L}+)", "x", BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void patternWithoutProductGroupIsRejected() {
    assertThatThrownBy(() -> parser.parse("(?<amount>[0-9]+,[0-9]{2})", "x", BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("product");
  }

  @Test
  void uncompilablePatternIsRejected() {
    assertThatThrownBy(() -> parser.parse("(?<product>[", "x", BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullRemittanceYieldsNothing() {
    var r = parser.parse(PATTERN, null, new BigDecimal("10.00"));
    assertThat(r.matched()).isFalse();
    assertThat(r.sumMismatch()).isFalse();
    assertThat(r.positions()).isEmpty();
  }

  @Test
  void sameProductTwiceIsReturnedTwiceAndFoldedLater() {
    // Folding is normalization-dependent and therefore NOT this class's job -- it happens in
    // ProductSplitResolver after the DB has normalized the names. The parser returns raw hits.
    var r = parser.parse(PATTERN, "P Health 100,00 Health 6,00", new BigDecimal("106.00"));
    assertThat(r.positions()).hasSize(2);
  }
}
