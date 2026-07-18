package de.visterion.aletheia.tagrules;

/** One rule condition {@code {field, op, value}} (spec §3). All conditions of a rule are AND-ed. */
public record RuleCondition(RuleField field, RuleOp op, String value) {}
