package de.visterion.aletheia.ingest;

/** Reduces a client-supplied filename to a single safe, {@code .json} filesystem component. */
public final class FileNameSanitizer {

  private static final String DEFAULT = "export.json";
  private static final int MAX_STEM = 120;

  private FileNameSanitizer() {}

  public static String sanitize(String raw) {
    if (raw == null) {
      return DEFAULT;
    }
    // Take only the last path component, whatever separator style the client used.
    String base = raw.replace('\\', '/');
    int slash = base.lastIndexOf('/');
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    // Drop anything that is not a safe filename character.
    base = base.replaceAll("[^A-Za-z0-9._-]", "");
    // Remove leading dots so "..", ".hidden" cannot survive.
    while (base.startsWith(".")) {
      base = base.substring(1);
    }
    if (base.isBlank()) {
      return DEFAULT;
    }
    String stem = base.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
        ? base.substring(0, base.length() - ".json".length())
        : base;
    if (stem.isBlank()) {
      return DEFAULT;
    }
    if (stem.length() > MAX_STEM) {
      stem = stem.substring(0, MAX_STEM);
    }
    return stem + ".json";
  }
}
