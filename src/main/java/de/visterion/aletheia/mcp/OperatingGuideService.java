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
        + productMismatchWarning()
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

  /**
   * The residue surface of the product grain (spec §6): one warning line, or nothing at all.
   *
   * <p>A booking whose parsed positions do not sum to the booking amount is left untouched by
   * {@code ProductSplitResolver} and pools into the mandate-level contract that the rollout ended
   * -- which no read surface shows, because the register selects {@code confirmed}, the review
   * queue selects {@code open} and {@code list_unmatched_recurring} excludes {@code ended}. A
   * creditor that silently reformats its remittance would therefore be under-reported from that
   * month on, with the only trace in container logs. This line is what makes it visible.
   *
   * <p><b>Only enabled rules warn.</b> Disabling a rule is a pause, not a revert (spec §5): the
   * resolver skips a paused rule entirely, so its counters are frozen at the moment of pausing and
   * can never rise again. Alarming on them would produce a warning on every {@code wake_up} that
   * no action clears. The frozen numbers stay readable through {@code list_product_rules}.
   *
   * <p>It renders as a single line even for several creditors, deliberately: {@code wake_up} was
   * cut from 82 lines to a live-state-first shape because prose here burns tokens before the first
   * real question is asked, and a warning that grows a line per creditor would reintroduce that.
   */
  private String productMismatchWarning() {
    var rows =
        db.fetch(
            "SELECT creditor_id, roots_mismatched FROM product_rules "
                + "WHERE enabled AND roots_mismatched > 0 ORDER BY creditor_id");
    if (rows.isEmpty()) {
      return "";
    }
    StringBuilder creditors = new StringBuilder();
    for (Record r : rows) {
      if (!creditors.isEmpty()) {
        creditors.append(", ");
      }
      creditors
          .append(r.get("creditor_id", String.class))
          .append(": ")
          .append(r.get("roots_mismatched", Integer.class));
    }
    return "- WARNING: unmatched product bookings ("
        + creditors
        + ") -- the parsed positions no longer sum to the booking amount; the creditor may have"
        + " changed its remittance format; inspect with list_product_rules, then re-author the"
        + " pattern with update_product_rule (dryRun first)\n";
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
