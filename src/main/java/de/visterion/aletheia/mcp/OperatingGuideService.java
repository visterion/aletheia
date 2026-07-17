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

  private final DSLContext db;

  public OperatingGuideService(DSLContext db) {
    this.db = db;
  }

  public String wakeUp() {
    Record guide =
        db.fetchOne(
            "SELECT workflow_md, preferences_md FROM operating_guide WHERE scope = 'default'");
    String workflow = guide == null ? "" : guide.get("workflow_md", String.class);
    String prefsRaw = guide == null ? "" : guide.get("preferences_md", String.class);
    String prefs = (prefsRaw == null || prefsRaw.isBlank()) ? "(none recorded yet)" : prefsRaw;

    long unreviewed =
        (Long) db.fetchValue("SELECT count(*) FROM counterparties WHERE reviewed = false");
    long opaquePassthroughs =
        (Long)
            db.fetchValue(
                "SELECT count(*) FROM counterparties c WHERE c.reviewed = false AND EXISTS ("
                    + "SELECT 1 FROM counterparty_tags t WHERE t.counterparty_id = c.id "
                    + "AND t.dimension = 'nature' AND t.value = 'zahlungsdienst')");
    long openContracts =
        (Long) db.fetchValue("SELECT count(*) FROM contracts WHERE status = 'open'");
    long confirmedContracts =
        (Long) db.fetchValue("SELECT count(*) FROM contracts WHERE status = 'confirmed'");

    return workflow
        + "\n\n# Customer preferences\n"
        + prefs
        + "\n\n# Current state (live)\n"
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
        + "\n";
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
