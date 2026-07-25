package de.visterion.aletheia.substrate;

import java.util.Objects;
import org.jooq.DSLContext;

/**
 * Shared SQL fragment for the counterparty-name normalization rule, and (via {@link
 * #evaluate(DSLContext, String)}) the means to execute that rule in the database and get its
 * result back into Java.
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

  /**
   * Evaluates both normal forms of {@code raw} in the database, in one roundtrip.
   *
   * <p>This exists because Java and PostgreSQL disagree on what the formula means: PostgreSQL's
   * {@code \s} class collapses a wider set of Unicode whitespace than Java's (and Java's
   * additionally requires {@code UNICODE_CHARACTER_CLASS} to get close, which still wouldn't
   * match); PostgreSQL's {@code trim()} strips only {@code U+0020} at the edges while Java's {@code
   * String.trim()} strips everything {@code <= U+0020}; and {@code upper()} depends on the
   * platform's libc collation (musl maps {@code straße -> STRAẞE}; Java's {@code
   * toUpperCase(Locale.ROOT)} has no {@code ß -> ẞ} mapping at all and would produce {@code
   * STRASSE}). Re-deriving the formula in Java would require hand-picking a whitespace character
   * class and would still not solve the {@code ß} divergence. Evaluating the formula in the same
   * engine and (in production) the same libc that already stores and enforces it makes the
   * returned value a fixpoint of {@link #displaySql(String)}/{@link #identitySql(String)} by
   * construction -- i.e. re-running the formula on the result yields the same result again -- which
   * is exactly the property a CHECK constraint built from this formula needs from any value a write
   * path hands it.
   *
   * @throws NullPointerException if raw is null. Unlike some historical call sites, this method
   *     does not special-case null into an empty result -- every caller is already null-guarded
   *     before reaching a normalization step, so a null-tolerant contract here would just be dead
   *     code enshrined in a new public API.
   */
  public static Normalized evaluate(DSLContext db, String raw) {
    Objects.requireNonNull(raw, "raw must not be null");
    org.jooq.Record record =
        db.resultQuery(
                "select "
                    + displaySql("cast(? as text)")
                    + " as d, "
                    + identitySql("cast(? as text)")
                    + " as i",
                raw,
                raw)
            .fetchOne();
    return new Normalized(record.get("d", String.class), record.get("i", String.class));
  }

  /**
   * Both fields non-null. {@code identity} is {@code upper(display)}, and {@code upper} maps
   * empty to empty, so {@code display.isEmpty()} holds exactly when {@code identity.isEmpty()}
   * does -- the two are never empty independently. See {@link #isEmpty()}, which relies on this.
   */
  public record Normalized(String display, String identity) {
    public boolean isEmpty() {
      return display.isEmpty();
    }
  }
}
