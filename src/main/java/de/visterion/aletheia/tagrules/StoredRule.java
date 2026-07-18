package de.visterion.aletheia.tagrules;

import java.util.List;

/** A rule as loaded from {@code tag_rules} (conditions/actions parsed from JSONB). */
public record StoredRule(
    long id, String name, boolean enabled, List<RuleCondition> conditions, List<RuleAction> actions) {}
