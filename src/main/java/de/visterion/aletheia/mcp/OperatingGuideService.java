package de.visterion.aletheia.mcp;

import java.time.format.DateTimeFormatter;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

/**
 * Owns the single operating_guide row: assembles the wake_up markdown (guide + preferences + a
 * counts-only live snapshot) and replaces the LLM-owned preferences section.
 */
@Component
public class OperatingGuideService {

  private static final String PREFERENCES_HEADING = "# Customer preferences";

  private final DSLContext db;

  public OperatingGuideService(DSLContext db) {
    this.db = db;
  }

  /** The protected, seeded operating guide -- served by the {@code read_me} tool. */
  public String operatingGuide() {
    Record guide = db.fetchOne("SELECT workflow_md FROM operating_guide WHERE scope = 'default'");
    return guide == null ? "" : guide.get("workflow_md", String.class);
  }

  public String wakeUp() {
    Record guide =
        db.fetchOne("SELECT preferences_md FROM operating_guide WHERE scope = 'default'");
    String prefsRaw = guide == null ? "" : guide.get("preferences_md", String.class);
    String stripped = prefsRaw == null ? "" : stripDuplicateHeading(prefsRaw).strip();
    String prefs = stripped.isBlank() ? "(none recorded yet)" : stripped;

    long unreviewed =
        (Long)
            db.fetchValue(
                "SELECT count(*) FROM counterparties WHERE reviewed = false "
                    + "AND merged_into IS NULL");
    long opaquePassthroughs =
        (Long)
            db.fetchValue(
                "SELECT count(*) FROM counterparties c WHERE c.reviewed = false "
                    + "AND c.merged_into IS NULL AND EXISTS ("
                    + "SELECT 1 FROM counterparty_tags t WHERE t.counterparty_id = c.id "
                    + "AND t.dimension = 'nature' AND t.value = 'zahlungsdienst')");
    long openContracts =
        (Long) db.fetchValue("SELECT count(*) FROM contracts WHERE status = 'open'");
    long confirmedContracts =
        (Long) db.fetchValue("SELECT count(*) FROM contracts WHERE status = 'confirmed'");

    return "# Aletheia — state\n"
        + "- Unreviewed counterparties: "
        + unreviewed
        + "\n"
        + "- Payment-service passthroughs still opaque: "
        + opaquePassthroughs
        + "\n"
        + "- Open contracts awaiting confirmation: "
        + openContracts
        + "\n"
        + "- Confirmed obligations: "
        + confirmedContracts
        + "\n"
        + "- Last import: "
        + lastImportLine()
        + "\n"
        + "\n# Customer preferences\n"
        + prefs
        + "\n\nOperating guide: read_me()\n";
  }

  /**
   * Drops a leading {@code # Customer preferences} line from the customer-owned text, because the
   * server emits that heading itself and would otherwise render it twice.
   *
   * <p>Deliberately matches that exact heading (case-insensitive, surrounding whitespace ignored)
   * and nothing else: a blanket "strip any leading H1" rule would also swallow a customer's own
   * opening heading and silently keep only its body. Leading blank lines are skipped before the
   * comparison, so a preferences text that merely starts with an empty line still has its
   * duplicate heading recognized and removed.
   */
  private static String stripDuplicateHeading(String preferences) {
    String[] lines = preferences.split("\n", -1);
    int i = 0;
    while (i < lines.length && lines[i].isBlank()) {
      i++;
    }
    if (i < lines.length && lines[i].strip().equalsIgnoreCase(PREFERENCES_HEADING)) {
      return String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
    }
    return preferences;
  }

  private String lastImportLine() {
    Record r =
        db.fetchOne(
            "SELECT file_name, period_start, period_end, imported_at FROM imports "
                + "ORDER BY imported_at DESC LIMIT 1");
    if (r == null) {
      return "(no imports yet)";
    }
    String file = r.get("file_name", String.class);
    var start = r.get("period_start", java.time.LocalDate.class);
    var end = r.get("period_end", java.time.LocalDate.class);
    var at = r.get("imported_at", java.time.OffsetDateTime.class);
    String period = (start != null && end != null) ? " (" + start + ".." + end + ")" : "";
    String when = at != null ? " on " + at.format(DateTimeFormatter.ISO_LOCAL_DATE) : "";
    return (file == null ? "(unnamed)" : file) + period + when;
  }

  public String updatePreferences(String preferencesMd, String actor) {
    int affected =
        db.execute(
            "UPDATE operating_guide SET preferences_md = ?, preferences_updated_at = now(), "
                + "preferences_updated_by = ? WHERE scope = 'default'",
            preferencesMd,
            actor);
    if (affected != 1) {
      throw new IllegalStateException(
          "operating_guide 'default' row missing (UPDATE affected " + affected + " rows)");
    }
    return "preferences updated";
  }
}
