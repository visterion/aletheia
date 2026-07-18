package de.visterion.aletheia.mcp;

import de.visterion.aletheia.tagrules.RuleAction;
import de.visterion.aletheia.tagrules.RuleCondition;
import java.util.List;

/** One row of {@code list_tag_rules}. */
public record TagRuleView(
    long id,
    String name,
    boolean enabled,
    List<RuleCondition> conditions,
    List<RuleAction> actions,
    String createdAt) {}
