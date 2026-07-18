package de.visterion.aletheia.tagrules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TagRuleValidationTest {

  private static final RuleCondition OK_COND =
      new RuleCondition(RuleField.remittance_info, RuleOp.contains, "telekom");
  private static final RuleAction OK_ACTION = new RuleAction("domain", "telekommunikation");

  @Test
  void acceptsAValidRule() {
    assertThatCode(() -> TagRuleValidator.validate(List.of(OK_COND), List.of(OK_ACTION)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsEmptyConditions() {
    assertThatThrownBy(() -> TagRuleValidator.validate(List.of(), List.of(OK_ACTION)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEmptyActions() {
    assertThatThrownBy(() -> TagRuleValidator.validate(List.of(OK_COND), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsContainsOnCreditorId() {
    assertThatThrownBy(
            () ->
                TagRuleValidator.validate(
                    List.of(new RuleCondition(RuleField.creditor_id, RuleOp.contains, "x")),
                    List.of(OK_ACTION)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBadDirectionValue() {
    assertThatThrownBy(
            () ->
                TagRuleValidator.validate(
                    List.of(new RuleCondition(RuleField.direction, RuleOp.equals, "BOTH")),
                    List.of(OK_ACTION)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsGoodDirectionValue() {
    assertThatCode(
            () ->
                TagRuleValidator.validate(
                    List.of(new RuleCondition(RuleField.direction, RuleOp.equals, "DBIT")),
                    List.of(OK_ACTION)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsBlankConditionValue() {
    assertThatThrownBy(
            () ->
                TagRuleValidator.validate(
                    List.of(new RuleCondition(RuleField.remittance_info, RuleOp.contains, "  ")),
                    List.of(OK_ACTION)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownDimension() {
    assertThatThrownBy(
            () ->
                TagRuleValidator.validate(List.of(OK_COND), List.of(new RuleAction("bogus", "x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankActionValue() {
    assertThatThrownBy(
            () -> TagRuleValidator.validate(List.of(OK_COND), List.of(new RuleAction("domain", " "))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateActionPair() {
    assertThatThrownBy(
            () ->
                TagRuleValidator.validate(
                    List.of(OK_COND),
                    List.of(new RuleAction("domain", "x"), new RuleAction("domain", "x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullFieldsInCondition() {
    assertThatThrownBy(
            () ->
                TagRuleValidator.validate(
                    List.of(new RuleCondition(null, RuleOp.equals, "x")), List.of(OK_ACTION)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
