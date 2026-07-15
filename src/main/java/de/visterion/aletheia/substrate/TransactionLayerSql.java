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
}
