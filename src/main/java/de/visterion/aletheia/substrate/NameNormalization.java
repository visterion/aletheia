package de.visterion.aletheia.substrate;

/**
 * Shared SQL fragment for the counterparty-name normalization rule.
 *
 * <p>Two normal forms are produced from a raw name expression:
 *
 * <ul>
 *   <li><b>display</b> ({@link #displaySql(String)}) — {@code
 *       trim(regexp_replace(normalize(x, NFC), '\s+', ' ', 'g'))}: NFC-normalize, then collapse
 *       runs of whitespace to a single space, then trim. Used wherever a human-facing name is
 *       shown (display names, matcher inputs before case-folding).
 *   <li><b>identity</b> ({@link #identitySql(String)}) — {@code upper(displaySql(x))}: the same
 *       rule, upper-cased, for use as (part of) an identity/dedup key.
 * </ul>
 *
 * <p><b>The formula is frozen.</b> The live definitions of {@code v_counterparty_evidence} and
 * {@code v_contract_evidence} carry this exact expression verbatim inside {@code
 * V15__counterparty_alias.sql}, and Flyway migrations are immutable once applied. This class
 * shares the rule so future call sites don't hand-copy it again — it is not a knob to turn.
 * Changing the formula would require a view-rebuild migration (new views, or a versioned
 * replacement) plus a full re-resolve of every counterparty/contract/tag-rule row that depends on
 * it, and is its own project, not a side effect of touching this class.
 *
 * <p><b>Engine coupling:</b> {@code upper()} in {@link #identitySql(String)} delegates to
 * PostgreSQL's collation support, which in turn delegates to the platform's libc. Under musl
 * {@code upper('straße')} yields {@code 'STRAẞE'}; under glibc it yields {@code 'STRAßE'}.
 * Production runs {@code postgres:16-alpine} (musl-based), and every Testcontainers usage in this
 * repo is pinned to the same image tag. That pin is load-bearing, not incidental — switching the
 * test image to a glibc-based Postgres would silently change identity-key collision behavior for
 * names containing eszett or other case-folding-sensitive characters, and tests would stop
 * reflecting production behavior.
 *
 * <p><b>Do not confuse this with {@link de.visterion.aletheia.ingest.ContentHash#normText}</b>,
 * which independently implements the same NFC+trim+collapse shape in Java for a different
 * purpose: it feeds {@code transactions.content_hash}, the persisted ingest natural key. The two
 * must never be unified — Java's {@code \s} only matches ASCII whitespace while PostgreSQL's
 * whitespace class is wider, so converging them would change already-persisted hashes and risk
 * duplicate imports. See {@code ContentHashGoldenTest} for the guard on that side.
 */
public final class NameNormalization {

  private NameNormalization() {}

  /**
   * Display-form normalization: NFC-normalize {@code inputExpr}, collapse whitespace runs to a
   * single space, then trim. {@code inputExpr} is inserted verbatim as a SQL expression (a column
   * reference or another expression), not a literal.
   */
  public static String displaySql(String inputExpr) {
    return "trim(regexp_replace(normalize(" + inputExpr + ", NFC), '\\s+', ' ', 'g'))";
  }

  /** Identity-form normalization: {@link #displaySql(String)}, upper-cased. */
  public static String identitySql(String inputExpr) {
    return "upper(" + displaySql(inputExpr) + ")";
  }
}
