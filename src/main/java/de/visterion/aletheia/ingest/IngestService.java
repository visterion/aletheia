package de.visterion.aletheia.ingest;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Dumb, lossless, idempotent ingest of one Subsembly export file. */
@Service
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final DSLContext db;
  private final TransactionTemplate tx;
  private final SubsemblyParser parser;

  public IngestService(DSLContext db, PlatformTransactionManager txManager, SubsemblyParser parser) {
    this.db = db;
    this.tx = new TransactionTemplate(txManager);
    this.parser = parser;
  }

  public ImportSummary ingest(Path file) {
    return ingest(file, file.getFileName().toString());
  }

  public ImportSummary ingest(Path file, String originalFileName) {
    try {
      return tx.execute(status -> doIngest(file, originalFileName));
    } catch (DataAccessException e) {
      if (isImportsFileShaDuplicate(e)) {
        log.info("Concurrent import of already-imported content; treating as skipped");
        return ImportSummary.skipped();
      }
      throw e;
    }
  }

  /**
   * True only for a Postgres SQLState 23505 unique violation on the {@code
   * imports_file_sha256_key} constraint. A unique violation aborts the transaction, so this
   * must be checked only after {@code tx.execute} has returned (rolled back), never inside
   * the transactional callback.
   */
  private static boolean isImportsFileShaDuplicate(DataAccessException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        String msg = String.valueOf(sql.getMessage());
        return msg.contains("imports_file_sha256_key");
      }
      t = t.getCause();
    }
    return false;
  }

  private ImportSummary doIngest(Path file, String originalFileName) {
    byte[] bytes = readBytes(file);
    String sha = sha256Hex(bytes);

    if (db.fetchExists(db.selectFrom(IMPORTS).where(IMPORTS.FILE_SHA256.eq(sha)))) {
      log.info("Skipping already-imported file {} (sha {})", file.getFileName(), sha);
      return ImportSummary.skipped();
    }

    List<SubsemblyBooking> all;
    try {
      all = parser.parse(new java.io.ByteArrayInputStream(bytes));
    } catch (IllegalArgumentException ignored) {
      // Sanitized message only -- the parser's own message may echo JSON content, so it is not
      // propagated here.
      throw new InvalidExportException("malformed or invalid Subsembly export");
    }
    List<SubsemblyBooking> booked = all.stream().filter(SubsemblyBooking::isBooked).toList();
    int pendingIgnored = all.size() - booked.size();

    LocalDate periodStart =
        booked.stream().map(b -> LocalDate.parse(b.bookgDt())).min(LocalDate::compareTo).orElse(null);
    LocalDate periodEnd =
        booked.stream().map(b -> LocalDate.parse(b.bookgDt())).max(LocalDate::compareTo).orElse(null);
    String accountId =
        booked.stream().map(SubsemblyBooking::acctId).filter(a -> a != null).findFirst().orElse(null);

    long importId =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, originalFileName)
            .set(IMPORTS.FILE_SHA256, sha)
            .set(IMPORTS.ACCOUNT_ID, accountId)
            .set(IMPORTS.PERIOD_START, periodStart)
            .set(IMPORTS.PERIOD_END, periodEnd)
            .set(IMPORTS.ROWS_PENDING_IGNORED, pendingIgnored)
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);

    Map<String, Integer> occurrence = new HashMap<>();
    for (SubsemblyBooking b : booked) {
      String hash;
      try {
        hash = b.contentHash();
      } catch (IllegalArgumentException ignored) {
        // Sanitized message only -- the underlying message may echo the raw amount/booking
        // content, so it is not propagated here (spec §8: invalid export -> 400, not 500).
        throw new InvalidExportException("invalid booking amount in export");
      }
      int idx = occurrence.merge(hash, 0, (old, z) -> old + 1);
      insertBooking(b, hash, idx, importId);
    }

    int rowsBooked = booked.size();
    int rowsNew = db.fetchCount(TRANSACTIONS, TRANSACTIONS.IMPORT_ID.eq(importId));
    int rowsSkipped = rowsBooked - rowsNew;

    db.update(IMPORTS)
        .set(IMPORTS.ROWS_BOOKED, rowsBooked)
        .set(IMPORTS.ROWS_NEW, rowsNew)
        .set(IMPORTS.ROWS_SKIPPED, rowsSkipped)
        .where(IMPORTS.ID.eq(importId))
        .execute();

    log.info(
        "Ingested {}: booked={} new={} skipped={} pendingIgnored={}",
        file.getFileName(), rowsBooked, rowsNew, rowsSkipped, pendingIgnored);
    return new ImportSummary(importId, rowsBooked, rowsNew, rowsSkipped, pendingIgnored, false);
  }

  private void insertBooking(SubsemblyBooking b, String hash, int idx, long importId) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, idx)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.ACCOUNT_ID, b.acctId())
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.parse(b.bookgDt()))
        .set(TRANSACTIONS.VALUE_DATE, b.valDt() == null ? null : LocalDate.parse(b.valDt()))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(ContentHash.normalizeAmount(b.amt())))
        .set(TRANSACTIONS.CURRENCY, b.amtCcy())
        .set(TRANSACTIONS.DIRECTION, b.cdtDbtInd())
        .set(TRANSACTIONS.BOOKING_STATUS, b.bookgSts())
        .set(TRANSACTIONS.BOOKING_TEXT, b.bookgTxt())
        .set(TRANSACTIONS.REMITTANCE_INFO, b.rmtInf())
        .set(TRANSACTIONS.GVC, b.gvc())
        .set(TRANSACTIONS.GVC_EXTENSION, b.gvcExtension())
        .set(TRANSACTIONS.PURPOSE_CODE, b.purpCd())
        .set(TRANSACTIONS.COUNTERPARTY_NAME, b.rmtdNm())
        .set(TRANSACTIONS.COUNTERPARTY_ULTIMATE_NAME, b.rmtdUltmtNm())
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, b.rmtdAcctIban())
        .set(TRANSACTIONS.COUNTERPARTY_BIC, b.rmtdAcctBic())
        .set(TRANSACTIONS.CREDITOR_ID, b.cdtrId())
        .set(TRANSACTIONS.MANDATE_ID, b.mndtId())
        .set(TRANSACTIONS.END_TO_END_ID, b.endToEndId())
        .set(TRANSACTIONS.SUBSEMBLY_ID, b.id())
        .set(TRANSACTIONS.RAW, JSONB.valueOf(b.raw().toString()))
        .onConflict(TRANSACTIONS.CONTENT_HASH, TRANSACTIONS.OCCURRENCE_INDEX)
        .doNothing()
        .execute();
  }

  private static byte[] readBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("cannot read " + file, e);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
