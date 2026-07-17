package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "aletheia.paypal.creditor-ids=SYNTH-PP-CREDITOR")
class PayPalAttributionResolverIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired PayPalAttributionResolver resolver;

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

  private long insertPp(long imp, String hash, String creditorId, String remittance) {
    return db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, hash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("10.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.REMITTANCE_INFO, remittance)
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .returning(TRANSACTIONS.ID)
        .fetchOne(TRANSACTIONS.ID);
  }

  private String attributedOf(long id) {
    return db.select(TRANSACTIONS.ATTRIBUTED_NAME)
        .from(TRANSACTIONS)
        .where(TRANSACTIONS.ID.eq(id))
        .fetchOne(TRANSACTIONS.ATTRIBUTED_NAME);
  }

  @Test
  void parsesMerchantFromStandardAndTrickyRemittances() {
    long imp = importId();
    long netflix = insertPp(imp, "r1", "SYNTH-PP-CREDITOR",
        "1051510530015/PP.5426.PP/. Fizz Media, Ihr Einkauf bei Fizz Media");
    long truncated = insertPp(imp, "r2", "SYNTH-PP-CREDITOR",
        ". Acme Streaming Services International Ltd, Ihr Einkauf bei Acme Streaming Services Interna");
    long spaces = insertPp(imp, "r3", "SYNTH-PP-CREDITOR",
        "1045068517241 PP.5426.PP . Pixel Games, Ihr Einkauf bei Pixel Games");
    long dotName = insertPp(imp, "r4", "SYNTH-PP-CREDITOR",
        ". St. Pauli Shop, Ihr Einkauf bei St. Pauli Shop");
    long commaName = insertPp(imp, "r5", "SYNTH-PP-CREDITOR",
        ". Acme, Inc., Ihr Einkauf bei Acme, Inc.");
    long emptyMerchant = insertPp(imp, "r6", "SYNTH-PP-CREDITOR",
        "1049786721611/PP.3088.PP/. , Ihr Einkauf bei");
    long addBalance = insertPp(imp, "r7", "SYNTH-PP-CREDITOR", "1045068534551/ADD TO BALANCE");
    long whitespaceMerchant = insertPp(imp, "r8", "SYNTH-PP-CREDITOR",
        ".   , Ihr Einkauf bei");
    long nonPaypal = insertPp(imp, "r9", "OTHER-CREDITOR",
        ". Fizz Media, Ihr Einkauf bei Fizz Media");

    resolver.resolve();

    assertThat(attributedOf(netflix)).isEqualTo("Fizz Media");
    assertThat(attributedOf(truncated)).isEqualTo("Acme Streaming Services International Ltd");
    assertThat(attributedOf(spaces)).isEqualTo("Pixel Games");
    assertThat(attributedOf(dotName)).isEqualTo("St. Pauli Shop");
    assertThat(attributedOf(commaName)).isEqualTo("Acme, Inc.");
    assertThat(attributedOf(emptyMerchant)).isNull();
    assertThat(attributedOf(addBalance)).isNull();
    assertThat(attributedOf(whitespaceMerchant)).isNull();
    assertThat(attributedOf(nonPaypal)).isNull(); // allowlist gates it
  }

  @Test
  void isIdempotentAndDoesNotClobberManualAttribution() {
    long imp = importId();
    long ppRow = insertPp(imp, "m1", "SYNTH-PP-CREDITOR",
        ". Fizz Media, Ihr Einkauf bei Fizz Media");
    long manual = insertPp(imp, "m2", "SYNTH-PP-CREDITOR",
        ". Acme Streaming, Ihr Einkauf bei Acme Streaming");
    db.update(TRANSACTIONS)
        .set(TRANSACTIONS.ATTRIBUTED_NAME, "Human Choice")
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "manual")
        .where(TRANSACTIONS.ID.eq(manual))
        .execute();

    int firstRun = resolver.resolve();
    int secondRun = resolver.resolve();

    assertThat(firstRun).isEqualTo(1); // only ppRow; manual is protected
    assertThat(secondRun).isZero(); // idempotent
    assertThat(attributedOf(ppRow)).isEqualTo("Fizz Media");
    assertThat(attributedOf(manual)).isEqualTo("Human Choice"); // untouched
  }

  @Test
  void ignoresSplitChildren() {
    long imp = importId();
    long parent = insertPp(imp, "sp-parent", "SYNTH-PP-CREDITOR",
        ". Fizz Media, Ihr Einkauf bei Fizz Media");
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "sp-child")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.of(2026, 1, 1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("5.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "SYNTH-PP-CREDITOR")
        .set(TRANSACTIONS.REMITTANCE_INFO, ". Acme Streaming, Ihr Einkauf bei Acme Streaming")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, "sp-parent")
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();

    resolver.resolve();

    assertThat(attributedOf(parent)).isEqualTo("Fizz Media");
    String childAttributed =
        db.select(TRANSACTIONS.ATTRIBUTED_NAME)
            .from(TRANSACTIONS)
            .where(TRANSACTIONS.CONTENT_HASH.eq("sp-child"))
            .fetchOne(TRANSACTIONS.ATTRIBUTED_NAME);
    assertThat(childAttributed).isNull();
  }
}
