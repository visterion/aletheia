package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.jooq.Tables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two concurrent ingests of byte-identical content must never surface the underlying unique-key
 * violation as an exception; exactly one of them commits the {@code imports} row and the other
 * is mapped to {@link ImportSummary#skipped()}.
 */
class IngestServiceRaceIT extends AbstractPostgresIT {

  private static final String SYNTHETIC_ONE_BOOKING_JSON =
      "[{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\","
          + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
          + "\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}]";

  @Autowired IngestService ingestService;
  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  @Test
  void concurrentIdenticalIngestsProduceExactlyOneImportAndNoException(@TempDir Path dir)
      throws Exception {
    Path a = dir.resolve("a.json.part");
    Path b = dir.resolve("b.json.part");
    Files.writeString(a, SYNTHETIC_ONE_BOOKING_JSON);
    Files.writeString(b, SYNTHETIC_ONE_BOOKING_JSON); // identical content -> identical sha

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch latch = new CountDownLatch(1);
    Callable<ImportSummary> call =
        () -> {
          latch.await();
          return ingestService.ingest(a, "x.json");
        };
    Callable<ImportSummary> call2 =
        () -> {
          latch.await();
          return ingestService.ingest(b, "x.json");
        };
    Future<ImportSummary> f1 = pool.submit(call);
    Future<ImportSummary> f2 = pool.submit(call2);
    latch.countDown();

    ImportSummary s1 = f1.get(); // neither throws
    ImportSummary s2 = f2.get();
    pool.shutdown();

    assertThat(s1.fileAlreadyImported() ^ s2.fileAlreadyImported()).isTrue();
    assertThat(db.fetchCount(Tables.IMPORTS)).isEqualTo(1);
  }
}
