package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNameSanitizerTest {

  @Test
  void keepsSimpleJsonName() {
    assertThat(FileNameSanitizer.sanitize("girokonto.json")).isEqualTo("girokonto.json");
  }

  @Test
  void stripsPathTraversalAndSeparators() {
    assertThat(FileNameSanitizer.sanitize("../../etc/passwd")).doesNotContain("/", "..");
    assertThat(FileNameSanitizer.sanitize("..\\..\\x.json")).doesNotContain("\\", "..");
    assertThat(FileNameSanitizer.sanitize("/abs/path/a.json")).isEqualTo("a.json");
  }

  @Test
  void appendsJsonWhenMissing() {
    assertThat(FileNameSanitizer.sanitize("export")).endsWith(".json");
  }

  @Test
  void blankBecomesDefault() {
    assertThat(FileNameSanitizer.sanitize("   ")).isEqualTo("export.json");
    assertThat(FileNameSanitizer.sanitize(null)).isEqualTo("export.json");
  }

  @Test
  void boundsLength() {
    String longName = "a".repeat(500) + ".json";
    assertThat(FileNameSanitizer.sanitize(longName).length()).isLessThanOrEqualTo(125);
  }

  @Test
  void embeddedDoubleDotNeverSurvives() {
    String result = FileNameSanitizer.sanitize("report..json");
    assertThat(result).doesNotContain("..");
    assertThat(result).endsWith(".json");
  }

  @Test
  void truncationExposingTrailingDotNeverSurvives() {
    String longName = "a".repeat(119) + "." + "b.json";
    String result = FileNameSanitizer.sanitize(longName);
    assertThat(result).doesNotContain("..");
    assertThat(result).endsWith(".json");
  }
}
