package de.visterion.aletheia.substrate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns a remittance text into product positions, using a rule's position pattern applied globally
 * ({@link Matcher#find()} in a loop). One pattern matches one position, so a variable number of
 * positions works without a combinatorial pattern.
 *
 * <p>The class is pure: no database, no Spring, and deliberately <strong>no normalization and no
 * folding</strong>. Both key on the SQL normal form, which Java cannot reproduce — PostgreSQL's
 * {@code \s} collapses a wider character class and {@code upper()} delegates to libc (see {@link
 * NameNormalization}). A Java-side normal form would either violate the V19 CHECK constraints at
 * runtime or, worse, pass them while differing from the SQL form and mint two contracts for one
 * product. The parser therefore returns raw hits in match order; {@code ProductSplitResolver} folds
 * them after the database has normalized the names.
 *
 * <p>The exact-sum guard is the integrity precondition of the split: if the positions do not sum to
 * the booking amount the parse yields <em>no</em> positions at all, so no caller can accidentally
 * consume a mismatched parse. There is no tolerance and no epsilon — a creditor that changes its
 * format must surface as unmatched, not as a silently mis-attributed contract.
 */
public class ProductPositionParser {

  private static final String GROUP_PRODUCT = "product";
  private static final String GROUP_POLICY = "policy";
  private static final String GROUP_AMOUNT = "amount";

  /**
   * Parses {@code remittance} with {@code positionPattern}.
   *
   * @param positionPattern a regex declaring the named groups {@code product} and {@code amount},
   *     optionally {@code policy}
   * @param remittance the remittance text of the booking; may be {@code null}
   * @param bookingAmount the booking amount the positions must sum to exactly
   * @return the parse result; never {@code null}
   * @throws IllegalArgumentException if the pattern does not compile or lacks a required group
   */
  public ParseResult parse(String positionPattern, String remittance, BigDecimal bookingAmount) {
    Pattern pattern = compile(positionPattern);
    boolean hasPolicyGroup = pattern.namedGroups().containsKey(GROUP_POLICY);

    if (remittance == null || remittance.isEmpty()) {
      return ParseResult.noHits();
    }

    List<ProductPosition> positions = new ArrayList<>();
    BigDecimal sum = BigDecimal.ZERO;
    Matcher matcher = pattern.matcher(remittance);
    while (matcher.find()) {
      BigDecimal amount = germanDecimal(matcher.group(GROUP_AMOUNT));
      String policyNo = hasPolicyGroup ? matcher.group(GROUP_POLICY) : null;
      positions.add(
          new ProductPosition(matcher.group(GROUP_PRODUCT), policyNo, amount, matcher.group()));
      sum = sum.add(amount);
    }

    if (positions.isEmpty()) {
      return ParseResult.noHits();
    }
    // compareTo, never equals: 100.00 and 100.0 are the same amount at different scales.
    if (sum.compareTo(bookingAmount) != 0) {
      return ParseResult.mismatch();
    }
    return new ParseResult(true, false, List.copyOf(positions));
  }

  private static Pattern compile(String positionPattern) {
    Pattern pattern;
    try {
      pattern = Pattern.compile(positionPattern);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("position pattern does not compile: " + e.getMessage(), e);
    }
    var groups = pattern.namedGroups();
    for (String required : List.of(GROUP_PRODUCT, GROUP_AMOUNT)) {
      if (!groups.containsKey(required)) {
        throw new IllegalArgumentException(
            "position pattern must declare the named capture group '" + required + "'");
      }
    }
    return pattern;
  }

  /** German decimal notation to {@link BigDecimal}: {@code 1.234,56} becomes {@code 1234.56}. */
  private static BigDecimal germanDecimal(String raw) {
    return new BigDecimal(raw.replace(".", "").replace(',', '.'));
  }

  /**
   * The outcome of one parse.
   *
   * @param matched whether the positions are usable, i.e. at least one hit summing exactly to the
   *     booking amount
   * @param sumMismatch whether there were hits but their sum differs from the booking amount
   * @param positions the raw positions in match order; empty unless {@code matched}
   */
  public record ParseResult(
      boolean matched, boolean sumMismatch, List<ProductPosition> positions) {

    static ParseResult noHits() {
      return new ParseResult(false, false, List.of());
    }

    static ParseResult mismatch() {
      return new ParseResult(false, true, List.of());
    }
  }
}
