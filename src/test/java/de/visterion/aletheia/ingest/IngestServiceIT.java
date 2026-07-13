package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.jooq.Tables;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

// @Transactional wraps each test method in its own transaction that Spring Test rolls back
// afterward, isolating the shared Testcontainers Postgres instance between test methods.
// IngestService's TransactionTemplate (PROPAGATION_REQUIRED, the default) joins this outer
// transaction, so production semantics (one commit per file) are unaffected in real runs.
@Transactional
class IngestServiceIT extends AbstractPostgresIT {

  @Autowired IngestService ingestService;
  @Autowired DSLContext db;

  private Path write(Path dir, String name, String json) throws Exception {
    Path f = dir.resolve(name);
    Files.writeString(f, json);
    return f;
  }

  private long txCount() {
    return db.fetchCount(Tables.TRANSACTIONS);
  }

  @Test
  void ingestsBookedRowsAndDropsPending(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    String json =
        """
        [
          {"Id":"1","OwnrAcctIBAN":"DE1","Amt":"49.99","AmtCcy":"EUR","CdtDbtInd":"DBIT",
           "BookgDt":"2026-08-01","BookgSts":"BOOK","RmtdNm":"A","RmtInf":"x","MndtId":"M1"},
          {"Id":"2","OwnrAcctIBAN":"DE1","Amt":"3.50","AmtCcy":"EUR","CdtDbtInd":"DBIT",
           "BookgDt":"2026-08-02","BookgSts":"PDNG","RmtdNm":"B","RmtInf":"y"}
        ]
        """;
    ImportSummary s = ingestService.ingest(write(dir, "synthetic-a.json", json));

    assertThat(s.rowsBooked()).isEqualTo(1);
    assertThat(s.rowsNew()).isEqualTo(1);
    assertThat(s.rowsPendingIgnored()).isEqualTo(1);
    assertThat(txCount()).isEqualTo(1);
  }

  @Test
  void identicalFileShortCircuitsViaSha(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    String json =
        "[{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\","
            + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
            + "\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}]";
    ingestService.ingest(write(dir, "synthetic-a.json", json));
    ImportSummary again = ingestService.ingest(write(dir, "synthetic-a-copy.json", json));

    assertThat(again.fileAlreadyImported()).isTrue();
    assertThat(db.fetchCount(Tables.IMPORTS)).isEqualTo(1); // no second imports row
    assertThat(txCount()).isEqualTo(1);
  }

  @Test
  void overlappingContentIsDeduplicated(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    String rowA =
        "{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\","
            + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
            + "\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}";
    String rowB =
        "{\"Id\":\"2\",\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"20.00\",\"AmtCcy\":\"EUR\","
            + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-02\",\"BookgSts\":\"BOOK\","
            + "\"RmtdNm\":\"B\",\"RmtInf\":\"y\"}";
    ingestService.ingest(write(dir, "file1.json", "[" + rowA + "]"));
    ImportSummary s2 = ingestService.ingest(write(dir, "file2.json", "[" + rowA + "," + rowB + "]"));

    assertThat(s2.rowsBooked()).isEqualTo(2);
    assertThat(s2.rowsNew()).isEqualTo(1); // rowA skipped, rowB new
    assertThat(s2.rowsSkipped()).isEqualTo(1);
    assertThat(txCount()).isEqualTo(2);
  }

  @Test
  void threeIdenticalRowsGetDistinctOccurrenceIndexes(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    String row =
        "{\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"3.50\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
            + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\",\"RmtdNm\":\"BAKERY\",\"RmtInf\":\"\"}";
    String json = "[" + row + "," + row + "," + row + "]";
    ingestService.ingest(write(dir, "a.json", json));
    ingestService.ingest(write(dir, "b.json", json)); // re-import keeps 3

    assertThat(txCount()).isEqualTo(3);
    assertThat(
            db.select(Tables.TRANSACTIONS.OCCURRENCE_INDEX)
                .from(Tables.TRANSACTIONS)
                .orderBy(Tables.TRANSACTIONS.OCCURRENCE_INDEX)
                .fetch(Tables.TRANSACTIONS.OCCURRENCE_INDEX))
        .containsExactly(0, 1, 2);
  }

  @Test
  void distinctPairSharingHashExceptMandateIsNeverMerged(
      @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    String a =
        "{\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"49.99\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
            + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\",\"RmtdNm\":\"INS\",\"RmtInf\":\"b\","
            + "\"MndtId\":\"MND-A\"}";
    String b = a.replace("MND-A", "MND-B");
    // partial then full, reversed order
    ingestService.ingest(write(dir, "partial.json", "[" + a + "]"));
    ingestService.ingest(write(dir, "full.json", "[" + b + "," + a + "]"));

    assertThat(txCount()).isEqualTo(2);
    assertThat(db.select(Tables.TRANSACTIONS.MANDATE_ID).from(Tables.TRANSACTIONS)
            .fetch(Tables.TRANSACTIONS.MANDATE_ID))
        .containsExactlyInAnyOrder("MND-A", "MND-B");
  }

  @Test
  void amountWithTooManyDecimalsFailsWholeFile(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    String json =
        "[{\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"1.005\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
            + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\",\"RmtdNm\":\"A\",\"RmtInf\":\"x\"}]";
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ingestService.ingest(write(dir, "bad.json", json)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(txCount()).isZero(); // rolled back
  }

  @Test
  void rawRoundTripsUnknownField(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    String json =
        "[{\"OwnrAcctIBAN\":\"DE1\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\","
            + "\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\",\"RmtdNm\":\"A\",\"RmtInf\":\"x\","
            + "\"FooBar\":\"keepme\"}]";
    ingestService.ingest(write(dir, "a.json", json));
    String raw =
        db.select(Tables.TRANSACTIONS.RAW).from(Tables.TRANSACTIONS).fetchOne(Tables.TRANSACTIONS.RAW).data();
    assertThat(raw).contains("FooBar").contains("keepme");
  }
}
