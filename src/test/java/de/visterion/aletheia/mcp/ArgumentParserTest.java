package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.tagrules.RuleAction;
import de.visterion.aletheia.tagrules.RuleCondition;
import de.visterion.aletheia.tagrules.RuleField;
import de.visterion.aletheia.tagrules.RuleOp;
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

  @Test
  void requiredBooleanParsesValue() {
    JsonNode arguments = mapper.readTree("{\"dryRun\":true}");

    assertThat(ArgumentParser.requiredBoolean(arguments, "dryRun")).isTrue();
  }

  @Test
  void requiredBooleanThrowsMcpArgumentExceptionWhenMissing() {
    assertThatThrownBy(() -> ArgumentParser.requiredBoolean(arguments(), "missing"))
        .isInstanceOf(McpArgumentException.class)
        .hasMessage("Missing missing");
  }

  @Test
  void requiredTxReferenceParsesContentHashAndOccurrenceIndex() {
    JsonNode arguments = mapper.readTree("{\"tx\":{\"contentHash\":\"abc\",\"occurrenceIndex\":2}}");

    TxReference tx = ArgumentParser.requiredTxReference(arguments, "tx");

    assertThat(tx).isEqualTo(new TxReference("abc", 2));
  }

  @Test
  void requiredTxReferenceListParsesMultipleRefs() {
    JsonNode arguments =
        mapper.readTree(
            "{\"refs\":[{\"contentHash\":\"a\",\"occurrenceIndex\":0},"
                + "{\"contentHash\":\"b\",\"occurrenceIndex\":1}]}");

    List<TxReference> refs = ArgumentParser.requiredTxReferenceList(arguments, "refs");

    assertThat(refs).containsExactly(new TxReference("a", 0), new TxReference("b", 1));
  }

  @Test
  void requiredTxReferenceListThrowsMcpArgumentExceptionWhenMissing() {
    assertThatThrownBy(() -> ArgumentParser.requiredTxReferenceList(arguments(), "refs"))
        .isInstanceOf(McpArgumentException.class)
        .hasMessage("Missing refs");
  }

  @Test
  void optionalAllocationListReturnsNullWhenAbsent() {
    assertThat(ArgumentParser.optionalAllocationList(arguments(), "allocations")).isNull();
  }

  @Test
  void optionalAllocationListParsesNullableFieldsAsNull() {
    JsonNode arguments =
        mapper.readTree(
            "{\"allocations\":[{\"amount\":5.00,\"counterpartyId\":null,\"displayName\":\"Bargeld\","
                + "\"mandateId\":null,\"remittanceInfo\":null}]}");

    List<Allocation> allocations = ArgumentParser.optionalAllocationList(arguments, "allocations");

    assertThat(allocations).hasSize(1);
    Allocation a = allocations.get(0);
    assertThat(a.counterpartyId()).isNull();
    assertThat(a.displayName()).isEqualTo("Bargeld");
    assertThat(a.mandateId()).isNull();
    assertThat(a.remittanceInfo()).isNull();
    assertThat(a.amount().compareTo(new BigDecimal("5.00"))).isZero();
  }

  @Test
  void requiredTagInputListParsesDimensionValuePairs() {
    JsonNode arguments =
        mapper.readTree("{\"tags\":[{\"dimension\":\"domain\",\"value\":\"insurance\"}]}");

    List<TagInput> tags = ArgumentParser.requiredTagInputList(arguments, "tags");

    assertThat(tags).containsExactly(new TagInput("domain", "insurance"));
  }

  @Test
  void requiredRuleConditionListParsesFieldOpValue() {
    JsonNode arguments =
        mapper.readTree(
            "{\"conditions\":[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"netflix\"}]}");

    List<RuleCondition> conditions = ArgumentParser.requiredRuleConditionList(arguments, "conditions");

    assertThat(conditions)
        .containsExactly(new RuleCondition(RuleField.remittance_info, RuleOp.contains, "netflix"));
  }

  @Test
  void requiredRuleConditionListThrowsMcpArgumentExceptionForInvalidEnum() {
    JsonNode arguments =
        mapper.readTree("{\"conditions\":[{\"field\":\"nope\",\"op\":\"contains\",\"value\":\"x\"}]}");

    assertThatThrownBy(() -> ArgumentParser.requiredRuleConditionList(arguments, "conditions"))
        .isInstanceOf(McpArgumentException.class)
        .hasMessage("Invalid field");
  }

  @Test
  void requiredRuleActionListParsesDimensionValuePairs() {
    JsonNode arguments = mapper.readTree("{\"actions\":[{\"dimension\":\"nature\",\"value\":\"fixed\"}]}");

    List<RuleAction> actions = ArgumentParser.requiredRuleActionList(arguments, "actions");

    assertThat(actions).containsExactly(new RuleAction("nature", "fixed"));
  }
}
