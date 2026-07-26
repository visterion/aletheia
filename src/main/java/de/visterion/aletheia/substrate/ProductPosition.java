package de.visterion.aletheia.substrate;

import java.math.BigDecimal;

/**
 * One position parsed out of a remittance.
 *
 * <p>All values are the <em>raw</em> capture values of the rule pattern. Identity is decided later
 * and in SQL: product names are normalized by PostgreSQL, never by Java (see {@link
 * NameNormalization}).
 *
 * @param rawProduct the product name exactly as it appeared in the remittance
 * @param policyNo the sub-policy number of this position, or {@code null} if the pattern has no
 *     {@code policy} group or it did not participate in the match
 * @param amount the position amount
 * @param matchedText the substring the pattern matched; becomes the child row's remittance info
 */
public record ProductPosition(
    String rawProduct, String policyNo, BigDecimal amount, String matchedText) {}
