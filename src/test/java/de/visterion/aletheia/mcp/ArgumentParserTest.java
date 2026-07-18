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

  @Test
  void counterpartySelectorReturnsNullWhenFieldAbsent() {
    CounterpartySelector where = ArgumentParser.counterpartySelector(arguments(), "where");

    assertThat(where).isNull();
  }

  @Test
  void counterpartySelectorParsesAllEightFields() {
    JsonNode arguments =
        mapper.readTree(
            "{\"where\":{\"untagged\":true,\"namePattern\":\"tel\",\"minAnnualCost\":12.5,"
                + "\"predominantDirection\":\"DBIT\",\"domainIn\":[\"insurance\"],"
                + "\"natureIn\":[\"fixed\"],\"reviewed\":false,\"hasContract\":true}}");

    CounterpartySelector where = ArgumentParser.counterpartySelector(arguments, "where");

    assertThat(where.untagged()).isTrue();
    assertThat(where.namePattern()).isEqualTo("tel");
    assertThat(where.minAnnualCost().compareTo(new BigDecimal("12.5"))).isZero();
    assertThat(where.predominantDirection()).isEqualTo(Direction.DBIT);
    assertThat(where.domainIn()).containsExactly("insurance");
    assertThat(where.natureIn()).containsExactly("fixed");
    assertThat(where.reviewed()).isFalse();
    assertThat(where.hasContract()).isTrue();
  }

  @Test
  void counterpartySelectorThrowsMcpArgumentExceptionForInvalidPredominantDirection() {
    JsonNode arguments = mapper.readTree("{\"where\":{\"predominantDirection\":\"NOPE\"}}");

    assertThatThrownBy(() -> ArgumentParser.counterpartySelector(arguments, "where"))
        .isInstanceOf(McpArgumentException.class)
        .hasMessage("Invalid predominantDirection");
  }

  @Test
  void requiredEnumParsesValidValue() {
    Direction direction = ArgumentParser.requiredEnum(arguments(), "e", Direction.class);

    assertThat(direction).isEqualTo(Direction.DBIT);
  }

  @Test
  void optionalEnumReturnsNullWhenFieldAbsent() {
    Direction direction = ArgumentParser.optionalEnum(arguments(), "missing", Direction.class);

    assertThat(direction).isNull();
  }
}
