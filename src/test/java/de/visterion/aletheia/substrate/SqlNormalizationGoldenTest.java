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
 * Freezes the seven composed SQL constants that still hand-copy the counterparty-name
 * normalization rule inline, before a later task substitutes them onto {@link NameNormalization}.
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

  private static String normalizeWhitespace(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
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
