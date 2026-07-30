package de.visterion.aletheia.mcp;

/**
 * One row of {@code list_product_rules}, including the four resolver counters written by the last
 * pass.
 *
 * <p>The counters are the residue surface: they answer "how much of this creditor's history does
 * this rule still explain?", so a creditor that silently reformats its remittance shows up as a
 * rising {@code rootsMismatched} instead of vanishing into container logs.
 */
public record ProductRuleView(
    long id,
    String creditorId,
    String positionPattern,
    boolean enabled,
    String notes,
    String createdAt,
    String lastResolvedAt,
    int rootsVisited,
    int rootsSplit,
    int rootsStamped,
    int rootsMismatched) {}
