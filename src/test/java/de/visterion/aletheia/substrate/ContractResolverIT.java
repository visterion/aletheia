package de.visterion.aletheia.substrate;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContractResolverIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  private static final String RAW = "{}";

  @AfterEach
  void cleanUp() {
    db.execute("TRUNCATE TABLE recurring, contracts, counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long seedCounterparty(String creditorId) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "creditor_id")
        .set(COUNTERPARTIES.IDENTITY_VALUE, creditorId)
        .set(COUNTERPARTIES.DISPLAY_NAME, creditorId)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void booking(String creditorId, String mandateId, String isoDate, String amount) {
    long imp =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "synthetic.json")
            .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "hash-" + UUID.randomUUID())
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.parse(isoDate))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .execute();
  }

  private int contractCount(String creditorId) {
    return db.fetchCount(
        CONTRACTS.join(COUNTERPARTIES).on(CONTRACTS.COUNTERPARTY_ID.eq(COUNTERPARTIES.ID)),
        COUNTERPARTIES.IDENTITY_VALUE.eq(creditorId));
  }

  private int recurringCountForContractsOf(long counterpartyId) {
    return db.fetchCount(RECURRING, RECURRING.COUNTERPARTY_ID.eq(counterpartyId));
  }

  @Test
  void elv_one_time_mandates_produce_zero_contracts() {
    seedCounterparty("CR-REWE");
    // 3 distinct mandates, each a single booking on one day -> ELV
    booking("CR-REWE", "ELV-1", "2025-01-05", "10.00");
    booking("CR-REWE", "ELV-2", "2025-02-05", "12.00");
    booking("CR-REWE", "ELV-3", "2025-03-05", "8.00");

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-REWE")).isZero();
  }

  @Test
  void two_recurring_mandates_split_into_two_contracts_with_recurring() {
    long cp = seedCounterparty("CR-DEBEKA");
    booking("CR-DEBEKA", "MND-A", "2025-01-01", "811.75");
    booking("CR-DEBEKA", "MND-A", "2025-02-01", "811.75");
    booking("CR-DEBEKA", "MND-B", "2025-01-01", "395.72");
    booking("CR-DEBEKA", "MND-B", "2025-02-01", "395.72");

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-DEBEKA")).isEqualTo(2);
    assertThat(recurringCountForContractsOf(cp)).isEqualTo(2);
  }

  @Test
  void same_month_twice_is_not_a_contract() {
    seedCounterparty("CR-BAKER");
    booking("CR-BAKER", "MND-X", "2025-01-10", "3.50");
    booking("CR-BAKER", "MND-X", "2025-01-20", "3.50");

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-BAKER")).isZero();
  }

  @Test
  void mixed_creditor_keeps_only_recurring_mandates() {
    seedCounterparty("CR-MIX");
    booking("CR-MIX", "REC-1", "2025-01-01", "20.00");
    booking("CR-MIX", "REC-1", "2025-02-01", "20.00");
    booking("CR-MIX", "REC-2", "2025-01-01", "30.00");
    booking("CR-MIX", "REC-2", "2025-03-01", "30.00");
    booking("CR-MIX", "ONE-1", "2025-01-01", "5.00");
    booking("CR-MIX", "ONE-2", "2025-01-02", "6.00");
    booking("CR-MIX", "ONE-3", "2025-01-03", "7.00");

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-MIX")).isEqualTo(2);
  }

  @Test
  void second_run_is_idempotent_and_does_not_downgrade_confirmed() {
    seedCounterparty("CR-IDEM");
    booking("CR-IDEM", "MND-I", "2025-01-01", "50.00");
    booking("CR-IDEM", "MND-I", "2025-02-01", "50.00");
    new ContractResolver(db).run(null);
    // human confirms the contract + its recurring
    db.update(CONTRACTS).set(CONTRACTS.SOURCE, "confirmed").set(CONTRACTS.STATUS, "confirmed").execute();
    db.update(RECURRING).set(RECURRING.SOURCE, "confirmed").execute();

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-IDEM")).isEqualTo(1);
    String src = db.select(RECURRING.SOURCE).from(RECURRING).fetchOne(RECURRING.SOURCE);
    assertThat(src).isEqualTo("confirmed"); // measured refresh must not downgrade source
  }

  @Test
  void reimport_with_raised_amount_refreshes_typical_amount() {
    seedCounterparty("CR-RAISE");
    booking("CR-RAISE", "MND-R", "2025-01-01", "811.75");
    booking("CR-RAISE", "MND-R", "2025-02-01", "811.75");
    new ContractResolver(db).run(null);
    // premium raised; new bookings ingested
    booking("CR-RAISE", "MND-R", "2025-03-01", "830.00");
    booking("CR-RAISE", "MND-R", "2025-04-01", "830.00");

    new ContractResolver(db).run(null);

    BigDecimal typical =
        db.select(RECURRING.TYPICAL_AMOUNT).from(RECURRING).fetchOne(RECURRING.TYPICAL_AMOUNT);
    // median of {811.75, 811.75, 830.00, 830.00} = 820.875 -> stored per NUMERIC(15,2)
    assertThat(typical).isGreaterThan(new BigDecimal("811.75"));
  }

  @Test
  void ignoresSplitChildrenSoTheyDoNotCreateContracts() {
    // TP2: ContractResolver must ignore children. Purchase child parts (even with mandate)
    // must not produce contracts. Only parent raw rows drive auto contracts.
    long cp = seedCounterparty("CR-CHILDIGNORE");

    // Two months of *child* rows (would qualify if counted, but must be ignored).
    String parentRef = "p-for-contract-ignore";
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-c1")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.parse("2025-01-01"))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("100.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "CR-CHILDIGNORE")
        .set(TRANSACTIONS.MANDATE_ID, "MND-CHILD")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parentRef)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, "child-c2")
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, (Long) null)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.parse("2025-02-01"))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("100.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "CR-CHILDIGNORE")
        .set(TRANSACTIONS.MANDATE_ID, "MND-CHILD")
        .set(TRANSACTIONS.RAW, JSONB.valueOf(RAW))
        .set(TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH, parentRef)
        .set(TRANSACTIONS.SPLIT_PARENT_OCCURRENCE_INDEX, 0)
        .execute();

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-CHILDIGNORE")).isZero();

    // Now add real parent rows (qualifying) -> contract appears.
    booking("CR-CHILDIGNORE", "MND-CHILD", "2025-03-01", "100.00");
    booking("CR-CHILDIGNORE", "MND-CHILD", "2025-04-01", "100.00");

    new ContractResolver(db).run(null);

    assertThat(contractCount("CR-CHILDIGNORE")).isEqualTo(1);
  }
}
