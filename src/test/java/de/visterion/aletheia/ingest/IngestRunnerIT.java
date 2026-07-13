package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.jooq.Tables;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class IngestRunnerIT extends AbstractPostgresIT {

  @Autowired IngestService ingestService;
  @Autowired DSLContext db;

  @Test
  void malformedFileIsSkippedAndGoodFileStillIngests(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("synthetic-bad.json"), "{ not valid json");
    Files.writeString(
        dir.resolve("synthetic-good.json"),
        "[{\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
            + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\",\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}]");

    var runner = new IngestRunner(new IngestProperties(dir), ingestService);
    runner.run(null);

    assertThat(db.fetchCount(Tables.TRANSACTIONS)).isEqualTo(1); // good file ingested, bad skipped
  }

  @Test
  void missingDirIsANoOp() throws Exception {
    var runner =
        new IngestRunner(new IngestProperties(Path.of("target/definitely-missing")), ingestService);
    runner.run(null); // must not throw
    assertThat(db.fetchCount(Tables.IMPORTS)).isZero();
  }
}
