package de.visterion.aletheia.mcp;

/**
 * What a product rule would do to a creditor's history, measured without writing anything (spec §8
 * step 3: check these numbers before committing the rule).
 *
 * @param rootsVisited raw roots of the creditor the resolver would consider (roots a human already
 *     split or re-attributed are excluded here exactly as the resolver excludes them)
 * @param bookingsMatched roots whose remittance parses into positions summing to the booking amount
 * @param positionsParsed positions across those roots, before folding by normalized product name
 * @param sumMismatches roots with positions that do <em>not</em> sum to the booking amount; those
 *     stay untouched and are the residue the {@code roots_mismatched} counter keeps visible
 */
public record ProductRuleBlastRadius(
    int rootsVisited, int bookingsMatched, int positionsParsed, int sumMismatches) {}
