package de.visterion.aletheia.ingest;

/** Result returned to the HTTP ingest endpoint after a successful upload. */
public record IngestResponse(
    String fileName,
    long importId,
    int rowsBooked,
    int rowsNew,
    int rowsSkipped,
    int rowsPendingIgnored,
    boolean fileAlreadyImported,
    boolean resolversRefreshed) {

  static IngestResponse from(String fileName, ImportSummary s) {
    return new IngestResponse(
        fileName,
        s.importId(),
        s.rowsBooked(),
        s.rowsNew(),
        s.rowsSkipped(),
        s.rowsPendingIgnored(),
        s.fileAlreadyImported(),
        true);
  }
}
