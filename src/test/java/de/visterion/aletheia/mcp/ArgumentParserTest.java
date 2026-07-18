package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ArgumentParserTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private JsonNode arguments() {
    return mapper.readTree(
        "{\"amount\":4.10,\"big\":123456789012345.67,\"ids\":[1,2],\"d\":\"2026-07-18\",\"e\":\"DBIT\"}");
  }

  @Test
  void requiredDecimalPreservesExactScaleForSimpleAmount() {
    BigDecimal amount = ArgumentParser.requiredDecimal(arguments(), "amount");

    assertThat(amount.compareTo(new BigDecimal("4.10"))).isZero();
  }

  @Test
  void requiredDecimalPreservesAllDigitsForLargeAmount() {
    BigDecimal big = ArgumentParser.requiredDecimal(arguments(), "big");

    assertThat(big.compareTo(new BigDecimal("123456789012345.67"))).isZero();
  }

  @Test
  void optionalLongListParsesArrayOfLongs() {
    List<Long> ids = ArgumentParser.optionalLongList(arguments(), "ids");

    assertThat(ids).containsExactly(1L, 2L);
  }

  @Test
  void requiredDateParsesIsoLocalDate() {
    LocalDate date = ArgumentParser.requiredDate(arguments(), "d");

    assertThat(date).isEqualTo(LocalDate.of(2026, 7, 18));
  }

  @Test
  void optionalTextReturnsNullWhenFieldAbsent() {
    String value = ArgumentParser.optionalText(arguments(), "missing");

    assertThat(value).isNull();
  }

  @Test
  void requiredTextThrowsMcpArgumentExceptionWhenMissing() {
    assertThatThrownBy(() -> ArgumentParser.requiredText(arguments(), "missing"))
        .isInstanceOf(McpArgumentException.class)
        .hasMessage("Missing missing");
  }

  @Test
  void requiredDecimalThrowsMcpArgumentExceptionForNonNumericValue() {
    assertThatThrownBy(() -> ArgumentParser.requiredDecimal(arguments(), "e"))
        .isInstanceOf(McpArgumentException.class)
        .hasMessage("Invalid e");
  }
}
