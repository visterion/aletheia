package de.visterion.aletheia.substrate;

/**
 * Shared SQL fragments for TP2 transaction layering.
 *
 * <p>Logical leaf = no child references this row (NOT EXISTS). Used by all business reads.
 *
 * <p>Raw root = {@code split_parent_content_hash IS NULL}. Used by substrate resolvers that must
 * never process synthetic children.
 */
public final class TransactionLayerSql {
  private TransactionLayerSql() {}

  /**
   * Predicate body (no leading AND/WHERE). {@code alias} is the transactions alias (e.g. "t" or
   * "i").
   */
  public static String notExistsSupersededParent(String alias) {
    return "NOT EXISTS ("
        + "SELECT 1 FROM transactions c WHERE c.split_parent_content_hash = "
        + alias
        + ".content_hash AND c.split_parent_occurrence_index = "
        + alias
        + ".occurrence_index)";
  }

  /** SQL fragment for resolvers: only bank/raw rows. */
  public static final String RAW_ROOT_PREDICATE = "split_parent_content_hash IS NULL";

  /**
   * The contract layer's row set: raw roots, except that a root superseded by <em>product</em>
   * children is replaced by those children (spec §6).
   *
   * <p>This is deliberately <b>not</b> a logical-leaf predicate. An ordinary human {@code
   * split_transaction} on a mandate booking also makes its parent a non-leaf, but its children
   * carry {@code product IS NULL} and usually neither creditor id nor mandate -- a leaf rule would
   * drop that month from the contract layer entirely, and {@code UPSERT_RECURRING}'s {@code DO
   * UPDATE} would then rewrite even a confirmed contract's measured columns down to the residue on
   * every pass. Only product children may demote their parent, because only they carry the parent's
   * creditor identity and mandate forward.
   *
   * <p>For a mandate without a product rule no row can carry a product, so the first branch
   * degenerates to {@link #RAW_ROOT_PREDICATE} and the second is always false: behaviour is
   * identical to before the product grain existed.
   *
   * <p>Complete, self-contained predicate body (no leading {@code AND}/{@code WHERE}); {@code
   * alias} is the transactions alias. Unlike {@link #RAW_ROOT_PREDICATE} it cannot be consumed as a
   * column-prefixed fragment, because it spans several columns and a subquery.
   */
  public static String contractGrainRootPredicate(String alias) {
    return "(("
        + alias
        + ".split_parent_content_hash IS NULL"
        + " AND NOT EXISTS (SELECT 1 FROM transactions c"
        + " WHERE c.split_parent_content_hash = "
        + alias
        + ".content_hash"
        + " AND c.split_parent_occurrence_index = "
        + alias
        + ".occurrence_index"
        + " AND c.product IS NOT NULL))"
        + " OR "
        + alias
        + ".product IS NOT NULL)";
  }
}
