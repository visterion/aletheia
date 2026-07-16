package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drives the full upload pipeline ({@link IngestEndpointService#ingestUpload}) against the
 * shared {@code target/test-no-ingest-dir} directory that {@link AbstractPostgresIT} points
 * {@code aletheia.ingest.dir} at (the startup runner no-ops there, spec §7 test isolation). Each
 * test cleans up the {@code incoming}/{@code imported} subdirectories it creates.
 */
class IngestEndpointServiceIT extends AbstractPostgresIT {

  private static final String SYNTHETIC_ONE_BOOKING_JSON =
      "[{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\","
          + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
          + "\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}]";

  @Autowired IngestEndpointService service;
  @Autowired IngestProperties properties;
  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() throws IOException {
    db.execute("TRUNCATE TABLE recurring, contracts, transactions, imports, counterparties "
        + "RESTART IDENTITY CASCADE");
    deleteRecursively(properties.dir().resolve("incoming"));
    deleteRecursively(properties.dir().resolve("imported"));
  }

  @Test
  void successArchivesFileAndReflectsStateWithoutRestart() throws Exception {
    IngestResponse r = service.ingestUpload(SYNTHETIC_ONE_BOOKING_JSON.getBytes(UTF_8), "girokonto.json");

    assertThat(r.fileName()).isEqualTo("girokonto.json");
    assertThat(r.rowsNew()).isGreaterThan(0);
    assertThat(r.resolversRefreshed()).isTrue();
    assertThat(list(properties.dir().resolve("incoming"))).isEmpty();
    assertThat(list(properties.dir().resolve("imported"))).hasSize(1);
  }

  @Test
  void malformedInputThrowsInvalidExportAndLeavesNoWorkingFile() throws Exception {
    assertThatThrownBy(() -> service.ingestUpload("not json".getBytes(UTF_8), "bad.json"))
        .isInstanceOf(InvalidExportException.class);

    assertThat(list(properties.dir().resolve("incoming"))).isEmpty();
  }

  private static java.util.List<Path> list(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return java.util.List.of();
    }
    try (var stream = Files.list(dir)) {
      return stream.toList();
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.delete(p);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }
  }
}
