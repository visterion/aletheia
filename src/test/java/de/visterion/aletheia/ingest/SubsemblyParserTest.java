package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubsemblyParserTest {

  private List<SubsemblyBooking> parseFixture() {
    try (var in = getClass().getResourceAsStream("/ingest/synthetic-basic.json")) {
      return new SubsemblyParser().parse(in);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void parsesTypedFieldsAndKeepsRaw() {
    var rows = parseFixture();
    assertThat(rows).hasSize(2);

    var booked = rows.get(0);
    assertThat(booked.isBooked()).isTrue();
    assertThat(booked.amtCcy()).isEqualTo("EUR");
    assertThat(booked.mndtId()).isEqualTo("MND-1");
    assertThat(booked.accountKey()).isEqualTo("DE00000000000000000001");
    // Unknown field survives in raw (semantic losslessness).
    assertThat(booked.raw().get("FooBar").asText()).isEqualTo("unknown-field-should-survive-in-raw");
  }

  @Test
  void pendingRowIsRecognized() {
    assertThat(parseFixture().get(1).isBooked()).isFalse();
  }

  @Test
  void accountKeyFallsBackToAcctIdWhenIbanBlank() {
    var b = new SubsemblyBooking(
        "x", "ACC-FALLBACK", null, "1.00", "EUR", "DBIT", "2026-08-01", "2026-08-01",
        "BOOK", null, null, null, null, null, null, null, null, null, null, null, null, null);
    assertThat(b.accountKey()).isEqualTo("ACC-FALLBACK");
  }

  @Test
  void nonArrayRootThrowsIllegalArgument() {
    var in = new ByteArrayInputStream(
        "{\"not\":\"an array\"}".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> new SubsemblyParser().parse(in))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedJsonThrows() {
    var in = new ByteArrayInputStream("{ not valid json".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> new SubsemblyParser().parse(in))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
