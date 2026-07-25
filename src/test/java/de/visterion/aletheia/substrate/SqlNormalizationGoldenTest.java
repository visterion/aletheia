package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.mcp.ReadTools;
import de.visterion.aletheia.tagrules.TagRuleResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the composed SQL of the seven call sites that build the counterparty-name normalization
 * rule through {@link NameNormalization}, so a future edit cannot silently change the composed
 * SQL beyond whitespace.
 *
 * <p>This test exists to catch exactly one failure mode: swapping {@link
 * NameNormalization#displaySql(String)} for {@link NameNormalization#identitySql(String)} (or
 * vice versa) at one of the sixteen call sites. The two forms differ only by an {@code upper(...)}
 * wrapper, which is easy to drop or add by accident when editing multi-line SQL text blocks. If a
 * substitution silently flips a site between "display" and "identity" form, the resulting SQL
 * still compiles and still runs — it just resolves identities or displays names case-sensitively
 * (or case-insensitively) in a way that differs from before, which would only surface later as a
 * counterparty-fragmentation or display-name regression. Comparing the whitespace-normalized SQL
 * against a golden captured from the current (correct) constants makes that flip fail loudly and
 * immediately, at the point of substitution.
 *
 * <p>Each constant is read by reflection because the fields are {@code private static final} in
 * their own classes and this test does not want to weaken that visibility just to be inspectable.
 * Comparison is whitespace-normalized (all whitespace runs collapsed to a single space, then
 * trimmed) because the seven constants are multi-line text blocks indented differently per call
 * site, while {@link NameNormalization#displaySql(String)} produces a single-line string — only
 * the token sequence needs to match, not the formatting.
 */
class SqlNormalizationGoldenTest {

  private static final Path GOLDEN_DIR = Path.of("src/test/resources/sql-golden");

  private record ConstantRef(Class<?> declaringClass, String fieldName) {
    String goldenFileName() {
      return fieldName + ".sql";
    }

    String qualifiedName() {
      return declaringClass.getName() + "#" + fieldName;
    }
  }

  private static final ConstantRef[] CONSTANTS = {
    new ConstantRef(CounterpartyResolver.class, "UPSERT_COUNTERPARTIES"),
    new ConstantRef(CounterpartyResolver.class, "REFRESH_DISPLAY_NAMES"),
    new ConstantRef(ContractResolver.class, "UPSERT_CONTRACTS"),
    new ConstantRef(ContractResolver.class, "UPSERT_RECURRING"),
    new ConstantRef(TagRuleResolver.class, "MATCH_BASE"),
    new ConstantRef(ReadTools.class, "COUNTERPARTY_TRANSACTIONS_SQL"),
    new ConstantRef(ReadTools.class, "IDENTITY_RESOLVED_TRANSACTIONS_SQL"),
  };

  private static String readConstant(ConstantRef ref) {
    try {
      Field field = ref.declaringClass().getDeclaredField(ref.fieldName());
      field.setAccessible(true);
      return (String) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Could not read " + ref.qualifiedName() + " by reflection", e);
    }
  }

  /**
   * Collapses whitespace runs to a single space and trims, then strips whitespace adjacent to
   * {@code (}, {@code )} and {@code ,}. The punctuation pass compensates for the pre-refactor
   * source formatting: two of the seven constants used to break {@code
   * trim(regexp_replace(...))} across lines with the opening/closing parens on their own line
   * (e.g. {@code regexp_replace(\n  normalize(...)...\n)}), which left a literal separator space
   * next to those parens once collapsed. {@link NameNormalization#displaySql(String)} composes
   * the same formula as one unbroken line with no such separator. Both forms are the same SQL --
   * whitespace next to punctuation carries no meaning to the parser -- so the comparison must
   * treat them as equal without touching the goldens, which are only evidentiary if they still
   * reflect the pre-substitution source. This does not weaken the one failure mode the test
   * exists to catch: swapping {@code displaySql} for {@code identitySql} adds or removes an
   * {@code upper(} token, which no amount of whitespace normalization can hide. The space inside
   * the {@code '\s+', ' ', 'g'} literal arguments is untouched because it sits between quote
   * characters, never adjacent to {@code (}, {@code )} or {@code ,}.
   *
   * <p>Residual blind spot, stated honestly: the punctuation pass runs over the whole constant,
   * not just the two historically wrapped fragments. A future string literal whose own content
   * has whitespace abutting a paren or comma -- {@code 'a, b'} edited to {@code 'a,b'} -- would
   * compare equal here. No such literal exists in the seven constants today (audited: none
   * contains a paren or comma), so this is a caveat for whoever adds one, not a live gap.
   */
  private static String normalizeWhitespace(String sql) {
    String collapsed = sql.replaceAll("\\s+", " ").trim();
    return collapsed.replaceAll("\\s*([(),])\\s*", "$1");
  }

  private static String readGolden(ConstantRef ref) {
    Path path = GOLDEN_DIR.resolve(ref.goldenFileName());
    try {
      return normalizeWhitespace(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Golden file missing: " + path, e);
    }
  }

  @Test
  void allSevenConstantsMatchTheirGoldens() {
    SoftAssertions softly = new SoftAssertions();
    for (ConstantRef ref : CONSTANTS) {
      String actual = normalizeWhitespace(readConstant(ref));
      String golden;
      try {
        golden = readGolden(ref);
      } catch (UncheckedIOException e) {
        softly.fail(
            "Golden file for "
                + ref.qualifiedName()
                + " is missing at "
                + GOLDEN_DIR.resolve(ref.goldenFileName())
                + ". Create it with this normalized content:\n"
                + actual);
        continue;
      }
      softly
          .assertThat(actual)
          .withFailMessage(
              "Constant %s no longer matches its golden. If this is an intentional change,"
                  + " regenerate the golden with the normalized actual value below (verify it's"
                  + " still the correct display/identity form before doing so):%n%s",
              ref.qualifiedName(),
              actual)
          .isEqualTo(golden);
    }
    softly.assertAll();
  }

  @Test
  void identitySqlWrapsDisplaySqlInUpper() {
    assertThat(NameNormalization.identitySql("x"))
        .isEqualTo("upper(trim(regexp_replace(normalize(x, NFC), '\\s+', ' ', 'g')))");
  }
}
