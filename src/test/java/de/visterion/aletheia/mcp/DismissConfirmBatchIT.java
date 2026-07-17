package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Batch (ids/where) mode for {@code dismiss_counterparty} (Task 3). */
class DismissConfirmBatchIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long seedCp(String name) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, "name")
        .set(COUNTERPARTIES.IDENTITY_VALUE, name + "-" + UUID.randomUUID())
        .set(COUNTERPARTIES.DISPLAY_NAME, name)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void tag(long cpId, String dimension, String value) {
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, cpId)
        .set(COUNTERPARTY_TAGS.DIMENSION, dimension)
        .set(COUNTERPARTY_TAGS.VALUE, value)
        .set(COUNTERPARTY_TAGS.SOURCE, "auto")
        .execute();
  }

  private void addContract(long cpId) {
    db.insertInto(CONTRACTS)
        .set(CONTRACTS.COUNTERPARTY_ID, cpId)
        .set(CONTRACTS.MANDATE_ID, "M-" + cpId)
        .set(CONTRACTS.SOURCE, "auto")
        .set(CONTRACTS.STATUS, "open")
        .execute();
  }

  @Test
  void batchDismissByIdsSetsStatusAndReviewed() {
    long a = seedCp("a");
    long b = seedCp("b");
    var ack = writeTools.dismissCounterparty(null, null, List.of(a, b), null, "noise", null);
    assertThat(ack.affectedCount()).isEqualTo(2);
    assertThat(
            db.fetchCount(
                COUNTERPARTIES,
                COUNTERPARTIES.STATUS.eq("dismissed").and(COUNTERPARTIES.REVIEWED.eq(true))))
        .isEqualTo(2);
  }

  @Test
  void batchDismissByWhereLeavesContractedUntouched() {
    long noise = seedCp("noise");
    tag(noise, "domain", "transfer-privat");
    long obligation = seedCp("ob");
    tag(obligation, "domain", "transfer-privat");
    addContract(obligation);
    var where = new CounterpartySelector(null, null, null, null, List.of("transfer-privat"), null, false, false);
    var ack = writeTools.dismissCounterparty(null, null, null, where, "sweep", null);
    assertThat(ack.affectedCount()).isEqualTo(1);
    assertThat(status(noise)).isEqualTo("dismissed");
    assertThat(status(obligation)).isNotEqualTo("dismissed");
  }

  @Test
  void batchDismissRejectsContractId() {
    assertThatThrownBy(() -> writeTools.dismissCounterparty(null, 7L, List.of(1L), null, "x", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void batchDismissRejectsIdsAndWhereTogether() {
    var where = new CounterpartySelector(null, null, null, null, List.of("x"), null, null, null);
    assertThatThrownBy(() -> writeTools.dismissCounterparty(null, null, List.of(1L), where, "x", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void batchDismissRejectsZeroEffectiveWhere() {
    var untaggedFalse = new CounterpartySelector(false, null, null, null, null, null, null, null);
    assertThatThrownBy(() -> writeTools.dismissCounterparty(null, null, null, untaggedFalse, "x", null))
        .isInstanceOf(IllegalArgumentException.class);
    var blankName = new CounterpartySelector(null, "", null, null, null, null, null, null);
    assertThatThrownBy(() -> writeTools.dismissCounterparty(null, null, null, blankName, "x", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void batchDismissGuardOver200() {
    List<Long> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 201; i++) {
      ids.add(seedCp("m" + i));
    }
    assertThatThrownBy(() -> writeTools.dismissCounterparty(null, null, ids, null, "x", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("201");
    var ack = writeTools.dismissCounterparty(null, null, ids, null, "x", true);
    assertThat(ack.affectedCount()).isEqualTo(201);
  }

  @Test
  void singleItemModeWithEmptyIdsList() { // m1
    long a = seedCp("a");
    var ack = writeTools.dismissCounterparty(a, null, List.of(), null, "solo", null);
    assertThat(ack.affectedCount()).isEqualTo(1);
    assertThat(status(a)).isEqualTo("dismissed");
  }

  @Test
  void batchRollsBackOnBadId() { // m4
    long good = seedCp("good");
    assertThatThrownBy(
            () -> writeTools.dismissCounterparty(null, null, List.of(good, 999999L), null, "x", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(status(good)).isNotEqualTo("dismissed"); // rolled back
  }

  @Test
  void sweepConverges() { // m5 / Finding 3 (legacy + mandate-less mix, reviewed-only sweep)
    long legacy = seedCp("legacy");
    tag(legacy, "domain", "transfer-privat");
    long recurring = seedCp("rec");
    tag(recurring, "domain", "transfer-privat");
    seedMandatelessRecurring(recurring); // helper: recurring row, contract_id NULL
    var where = new CounterpartySelector(null, null, null, null, List.of("transfer-privat"), null, false, null);
    assertThat(writeTools.dismissCounterparty(null, null, null, where, "s", null).affectedCount())
        .isEqualTo(2);
    assertThat(writeTools.dismissCounterparty(null, null, null, where, "s", null).affectedCount())
        .isEqualTo(0);
  }

  @Test
  void batchConfirmMandatelessAppearsInRegister() { // C1 regression
    long cp = seedCp("rec");
    seedMandatelessRecurring(cp);
    var ack = writeTools.confirmCounterparty(null, null, List.of(cp), null, null);
    assertThat(ack.affectedCount()).isEqualTo(1);
    // a NULL-mandate contract was materialized + confirmed -> visible in the register
    assertThat(
            db.fetchCount(
                CONTRACTS,
                CONTRACTS.COUNTERPARTY_ID.eq(cp).and(CONTRACTS.STATUS.eq("confirmed"))))
        .isEqualTo(1);
  }

  @Test
  void batchConfirmByIdsLegacyFlip() {
    long cp = seedCp("plain");
    tag(cp, "domain", "handel");
    writeTools.confirmCounterparty(null, null, List.of(cp), null, null);
    assertThat(
            db.select(COUNTERPARTIES.STATUS)
                .from(COUNTERPARTIES)
                .where(COUNTERPARTIES.ID.eq(cp))
                .fetchOne(COUNTERPARTIES.STATUS))
        .isEqualTo("confirmed");
    assertThat(
            db.fetchCount(
                COUNTERPARTIES,
                COUNTERPARTIES.ID.eq(cp).and(COUNTERPARTIES.REVIEWED.eq(true))))
        .isEqualTo(1);
  }

  @Test
  void batchConfirmRejectsContractId() {
    assertThatThrownBy(() -> writeTools.confirmCounterparty(null, 7L, List.of(1L), null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void openMandateContractNotConfirmedByBatch() { // §3 limitation
    long cp = seedCp("mandate");
    addContract(cp); // open mandate contract, no mandate-less recurring
    writeTools.confirmCounterparty(null, null, List.of(cp), null, null);
    assertThat(
            db.fetchCount(
                CONTRACTS, CONTRACTS.COUNTERPARTY_ID.eq(cp).and(CONTRACTS.STATUS.eq("open"))))
        .isEqualTo(1);
  }

  @Test
  void batchDismissMandatelessRecurringMaterializesAndDismisses() { // review coverage
    long cp = seedCp("rec-dismiss");
    seedMandatelessRecurring(cp);
    var ack = writeTools.dismissCounterparty(null, null, List.of(cp), null, "noise", null);
    assertThat(ack.affectedCount()).isEqualTo(1);
    assertThat(
            db.fetchCount(
                COUNTERPARTIES,
                COUNTERPARTIES.ID.eq(cp).and(COUNTERPARTIES.REVIEWED.eq(true))))
        .isEqualTo(1);
    assertThat(
            db.fetchCount(
                CONTRACTS,
                CONTRACTS.COUNTERPARTY_ID.eq(cp).and(CONTRACTS.STATUS.eq("dismissed"))))
        .isEqualTo(1);
  }

  @Test
  void dismissRejectsCounterpartyIdWithBatchIds() { // review coverage
    assertThatThrownBy(
            () ->
                writeTools.dismissCounterparty(
                    5L, null, List.of(1L), null, "reason", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String status(long id) {
    return db.select(COUNTERPARTIES.STATUS)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.ID.eq(id))
        .fetchOne(COUNTERPARTIES.STATUS);
  }

  private void seedMandatelessRecurring(long cpId) {
    db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, cpId)
        .set(RECURRING.CONTRACT_ID, (Long) null)
        .set(RECURRING.CADENCE, "monthly")
        .set(RECURRING.TYPICAL_AMOUNT, new java.math.BigDecimal("10.00"))
        .set(RECURRING.SOURCE, "auto")
        .execute();
  }
}
