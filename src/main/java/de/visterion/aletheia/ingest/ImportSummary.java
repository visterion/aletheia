package de.visterion.aletheia.ingest;

/** Result of one ingest run for a single Subsembly export file. */
public record ImportSummary(
    long importId,
    int rowsBooked,
    int rowsNew,
    int rowsSkipped,
    int rowsPendingIgnored,
    boolean fileAlreadyImported) {

  static ImportSummary skipped() {
    return new ImportSummary(-1, 0, 0, 0, 0, true);
  }
}
