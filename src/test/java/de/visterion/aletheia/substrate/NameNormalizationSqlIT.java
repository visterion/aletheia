package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.substrate.NameNormalization.Normalized;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies {@link NameNormalization#evaluate(DSLContext, String)} against a real {@code
 * postgres:16-alpine} engine -- the normalization rule is defined in terms of PostgreSQL's {@code
 * normalize}/{@code regexp_replace}/{@code trim}/{@code upper}, so it is only meaningfully testable
 * against the engine that owns it, not re-derivable from a Java-side model of Unicode whitespace.
 *
 * <p>The container is started once for the whole class (not per test), matching {@code
 * RegisterMigrationIT}'s idiom, because each test only issues a handful of cheap queries and
 * starting Postgres is the expensive part.
 */
@Testcontainers
class NameNormalizationSqlIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DSLContext db() {
    return DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * Renders a string as a sequence of {@code U+XXXX} codepoints for readable failure messages;
   * the empty string renders as {@code "(empty)"} rather than nothing, so a failure message never
   * has an invisible hole where the expectation should be.
   */
  private static String describe(String s) {
    if (s.isEmpty()) {
      return "(empty)";
    }
    StringBuilder sb = new StringBuilder();
    s.codePoints().forEach(cp -> sb.append(String.format("U+%04X ", cp)));
    return sb.toString().trim();
  }

  // Characters PostgreSQL's \s collapses that Java's does not.
  private static final String NEL = String.valueOf((char) 0x0085); // next line
  private static final String EM_SPACE = String.valueOf((char) 0x2003);
  private static final String IDEOGRAPHIC_SPACE = String.valueOf((char) 0x3000);
  private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);

  // Characters PostgreSQL's \s keeps (does NOT collapse/strip).
  private static final String NBSP = String.valueOf((char) 0x00A0);
  private static final String OGHAM_SPACE_MARK = String.valueOf((char) 0x1680);
  private static final String FIGURE_SPACE = String.valueOf((char) 0x2007);
  private static final String FILE_SEPARATOR = String.valueOf((char) 0x001C);

  // NFD "u" + combining diaeresis (U+0308) -> NFC "ü". Any ü-word proves the same property;
  // "Fühler" (feeler/sensor) is used rather than a loaded historical term.
  private static final String U_COMBINING_DIAERESIS = "u" + String.valueOf((char) 0x0308);
  private static final String U_UMLAUT_NFC = "ü";

  /**
   * The single corpus of input -> expected {@code display()} output, shared by every test that
   * needs "every corpus case" coverage (currently the pinned-expectation/idempotence test and the
   * CHECK-formula test) so the two can never drift apart: adding a case here automatically extends
   * both.
   */
  private static Map<String, String> corpus() {
    var cases = new LinkedHashMap<String, String>();
    cases.put("  Foo Bar  ", "Foo Bar");
    cases.put("Foo\tBar", "Foo Bar");
    cases.put("Foo\nBar", "Foo Bar");
    cases.put("Foo   Bar    Baz", "Foo Bar Baz");
    cases.put("Foo" + NEL + "Bar", "Foo Bar");
    cases.put("Foo" + EM_SPACE + "Bar", "Foo Bar");
    cases.put("Foo" + IDEOGRAPHIC_SPACE + "Bar", "Foo Bar");
    cases.put("Foo" + LINE_SEPARATOR + "Bar", "Foo Bar");
    cases.put("Foo" + NBSP + "Bar", "Foo" + NBSP + "Bar");
    cases.put("Foo" + OGHAM_SPACE_MARK + "Bar", "Foo" + OGHAM_SPACE_MARK + "Bar");
    cases.put("Foo" + FIGURE_SPACE + "Bar", "Foo" + FIGURE_SPACE + "Bar");
    cases.put("Foo" + FILE_SEPARATOR + "Bar", "Foo" + FILE_SEPARATOR + "Bar");
    cases.put("F" + U_COMBINING_DIAERESIS + "hler", "F" + U_UMLAUT_NFC + "hler");
    cases.put(NEL, "");
    cases.put(EM_SPACE, "");
    cases.put("   ", "");
    return cases;
  }

  @Test
  void corpusMatchesPinnedExpectationsAndIsIdempotent() {
    DSLContext db = db();
    SoftAssertions softly = new SoftAssertions();
    for (var entry : corpus().entrySet()) {
      String input = entry.getKey();
      String expected = entry.getValue();
      Normalized result = NameNormalization.evaluate(db, input);
      softly
          .assertThat(result.display())
          .withFailMessage(
              "input %s: expected display %s but got %s",
              describe(input), describe(expected), describe(result.display()))
          .isEqualTo(expected);

      // Idempotence: re-normalizing the already-normalized display form is a no-op.
      Normalized reapplied = NameNormalization.evaluate(db, result.display());
      softly
          .assertThat(reapplied.display())
          .withFailMessage(
              "input %s: evaluate() is not a fixpoint -- first pass %s, second pass %s",
              describe(input), describe(result.display()), describe(reapplied.display()))
          .isEqualTo(result.display());
    }
    softly.assertAll();
  }

  @Test
  void engineTripwireUpperOfEszettIsCapitalEszettUnderMusl() {
    String upper = db().fetchOne("select upper('straße')").get(0, String.class);
    assertThat(upper)
        .withFailMessage(
            "upper('straße') returned '%s', not 'STRAẞE'. This means the postgres:16-alpine image"
                + " is no longer musl-based, which shifts the identity normal form for names"
                + " containing eszett and would re-fragment counterparty identities in production."
                + " Fix the image pin (do not adjust this expectation).",
            upper)
        .isEqualTo("STRAẞE");
  }

  @Test
  void identityIsUppercasedDisplayForm() {
    Normalized result = NameNormalization.evaluate(db(), "  foo   bar  ");
    assertThat(result.display()).isEqualTo("foo bar");
    assertThat(result.identity()).isEqualTo("FOO BAR");
  }

  @Test
  void isEmptyIsTrueWhenInputNormalizesAway() {
    Normalized result = NameNormalization.evaluate(db(), "   ");
    assertThat(result.isEmpty()).isTrue();
    assertThat(result.display()).isEqualTo("");
    assertThat(result.identity()).isEqualTo("");
  }

  @Test
  void everyCorpusOutputSatisfiesTheCheckFormula() {
    DSLContext db = db();
    SoftAssertions softly = new SoftAssertions();
    for (String input : corpus().keySet()) {
      Normalized result = NameNormalization.evaluate(db, input);
      String value = result.display();
      // The V18 CHECK constraint (Task 6) will be: value = displaySql(value). Verify the fixpoint
      // property in SQL, on the output value itself -- not by comparing against evaluate()'s own
      // computation, which would only prove evaluate() is internally consistent.
      Boolean holds =
          db.resultQuery(
                  "select cast(? as text) = " + NameNormalization.displaySql("cast(? as text)"),
                  value,
                  value)
              .fetchOne(0, Boolean.class);
      softly
          .assertThat(holds)
          .withFailMessage(
              "input %s: output value %s does not satisfy the CHECK formula value ="
                  + " displaySql(value)",
              describe(input), describe(value))
          .isTrue();
    }
    softly.assertAll();
  }

  @Test
  void evaluateRejectsNull() {
    assertThatThrownBy(() -> NameNormalization.evaluate(db(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
