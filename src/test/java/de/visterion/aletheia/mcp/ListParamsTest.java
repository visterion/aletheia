package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ListParamsTest {

  @Test
  void nullsResolveToTheDocumentedDefaults() {
    ListParams params = new ListParams(null, null, null, null);
    assertThat(params.effectiveLimit()).isEqualTo(25);
    assertThat(params.effectiveOffset()).isZero();
    assertThat(params.effectiveVerbose()).isFalse();
    assertThat(params.minAmount()).isNull();
  }

  @Test
  void explicitValuesWin() {
    ListParams params = new ListParams(5, 10, new BigDecimal("2.50"), true);
    assertThat(params.effectiveLimit()).isEqualTo(5);
    assertThat(params.effectiveOffset()).isEqualTo(10);
    assertThat(params.effectiveVerbose()).isTrue();
    assertThat(params.minAmount()).isEqualByComparingTo("2.50");
  }

  @Test
  void explicitFalseVerboseResolvesToFalse() {
    // Distinct from nullsResolveToTheDocumentedDefaults: pins that effectiveVerbose() actually
    // checks for TRUE rather than merely "not null" (which would also pass for an explicit false).
    ListParams params = new ListParams(null, null, null, false);
    assertThat(params.effectiveVerbose()).isFalse();
  }

  @Test
  void theAcceptedEdgeOfEachGuardIsPinned() {
    // limit = 1 and offset = 0 are the smallest values each guard must still accept; without this,
    // tightening the guard to "limit <= 1" or "offset <= 0" leaves the rest of the suite green.
    ListParams params = new ListParams(1, 0, null, null);
    assertThat(params.effectiveLimit()).isEqualTo(1);
    assertThat(params.effectiveOffset()).isZero();
  }

  @Test
  void nonPositiveLimitIsRejected() {
    assertThatThrownBy(() -> new ListParams(0, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
    assertThatThrownBy(() -> new ListParams(-1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
  }

  @Test
  void negativeOffsetIsRejected() {
    assertThatThrownBy(() -> new ListParams(null, -1, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset");
  }

  @Test
  void aZeroOrNegativeMinAmountIsAccepted() {
    // A caller may legitimately want everything, including refunds; only limit/offset are bounded.
    assertThat(new ListParams(null, null, BigDecimal.ZERO, null).minAmount())
        .isEqualByComparingTo("0");
    assertThat(new ListParams(null, null, new BigDecimal("-5"), null).minAmount())
        .isEqualByComparingTo("-5");
  }
}
