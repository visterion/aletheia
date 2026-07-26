package de.visterion.aletheia.substrate;

/**
 * One {@code product_rules} row: a creditor's position pattern and whether it is active.
 *
 * <p>Rules live in the database, never in code (spec §2/D2). A hard-coded parser would put a real
 * creditor's remittance format into a public repository, and a new creditor would need a deploy.
 * The repo therefore ships the table empty; rows exist only on production.
 *
 * <p>The four {@code roots_*} counters and {@code last_resolved_at} are deliberately <em>not</em>
 * part of this record: they are written by {@link ProductSplitResolver} after a pass and read by
 * the tool layer, so carrying a stale copy through the resolver would only invite one.
 *
 * @param id primary key
 * @param creditorId the SEPA creditor identifier this rule applies to (unique per rule)
 * @param positionPattern regex matching ONE position, applied globally; must declare the named
 *     groups {@code product} and {@code amount}, optionally {@code policy}
 * @param enabled whether the resolver acts on this rule; disabling is a pause, not a revert
 * @param notes free-form operator note, or {@code null}
 */
public record ProductRule(
    long id, String creditorId, String positionPattern, boolean enabled, String notes) {}
