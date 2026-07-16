package de.visterion.aletheia.ingest;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the {@code ingest(Path, String)} overload stores the caller-supplied display name
 * instead of the on-disk path name, as needed for HTTP uploads written to a UUID working file.
 */
class IngestServiceFileNameIT extends AbstractPostgresIT {

  @Autowired IngestService ingestService;
  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  @Test
  void storesProvidedOriginalFileNameNotThePathName(@TempDir Path dir) throws Exception {
    String json =
        "[{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\","
            + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
            + "\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}]";
    Path working = dir.resolve("uuid-xyz.json.part");
    Files.writeString(working, json);

    ingestService.ingest(working, "girokonto.json");

    String stored = db.select(IMPORTS.FILE_NAME).from(IMPORTS).fetchOne(IMPORTS.FILE_NAME);
    assertThat(stored).isEqualTo("girokonto.json");
  }
}
