package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AttributionMigrationIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + java.util.UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private void insertWithAttributed(long imp, String hash, String attributed) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.ATTRIBUTED_NAME, attributed)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "manual")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void rejectsEmptyAttributedName() {
    long imp = importId();
    assertThatThrownBy(() -> insertWithAttributed(imp, "hash-empty", ""))
        .hasMessageContaining("chk_transactions_attributed_name_nonempty");
  }

  @Test
  void rejectsWhitespaceOnlyAttributedName() {
    long imp = importId();
    assertThatThrownBy(() -> insertWithAttributed(imp, "hash-blank", "   "))
        .hasMessageContaining("chk_transactions_attributed_name_nonempty");
  }
}
