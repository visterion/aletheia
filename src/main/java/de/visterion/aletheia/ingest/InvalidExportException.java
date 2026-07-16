package de.visterion.aletheia.ingest;

/** Deterministic, sanitized bad-input signal -- maps to HTTP 400. Message must carry no banking data. */
public class InvalidExportException extends RuntimeException {

  public InvalidExportException(String message) {
    super(message);
  }
}
