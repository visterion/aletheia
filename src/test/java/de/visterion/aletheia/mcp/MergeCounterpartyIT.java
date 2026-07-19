package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.CONTRACTS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_ALIAS;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_HISTORY;
import static de.visterion.aletheia.jooq.Tables.COUNTERPARTY_TAGS;
import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.RECURRING;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code merge_counterparty} core (spec {@code 2026-07-19-counterparty-merge-alias-design.md}
 * §3). Each test name maps to one bullet of the spec's Testing section.
 */
class MergeCounterpartyIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired ReadTools readTools;
  @Autowired CounterpartyResolver counterpartyResolver;
  @Autowired ContractResolver contractResolver;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_history, counterparty_tags, "
            + "counterparty_alias, tag_rules, counterparties, transactions, imports "
            + "RESTART IDENTITY CASCADE");
  }

  // --- fixture helpers ---

  private long importId() {
    return db.insertInto(IMPORTS)
        .set(IMPORTS.FILE_NAME, "synthetic.json")
        .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
        .returning(IMPORTS.ID)
        .fetchOne(IMPORTS.ID);
  }

  private long insertCounterparty(String identityType, String identityValue) {
    return db.insertInto(COUNTERPARTIES)
        .set(COUNTERPARTIES.IDENTITY_TYPE, identityType)
        .set(COUNTERPARTIES.IDENTITY_VALUE, identityValue)
        .set(COUNTERPARTIES.DISPLAY_NAME, identityValue)
        .returning(COUNTERPARTIES.ID)
        .fetchOne(COUNTERPARTIES.ID);
  }

  private void insertTag(long counterpartyId, String dimension, String value, String source) {
    db.insertInto(COUNTERPARTY_TAGS)
        .set(COUNTERPARTY_TAGS.COUNTERPARTY_ID, counterpartyId)
        .set(COUNTERPARTY_TAGS.DIMENSION, dimension)
        .set(COUNTERPARTY_TAGS.VALUE, value)
        .set(COUNTERPARTY_TAGS.SOURCE, source)
        .set(COUNTERPARTY_TAGS.CONFIDENCE, new BigDecimal("0.900"))
        .execute();
  }

  private long insertContract(long counterpartyId, String mandateId, String source, String status) {
    var step =
        db.insertInto(CONTRACTS)
            .set(CONTRACTS.COUNTERPARTY_ID, counterpartyId)
            .set(CONTRACTS.MANDATE_ID, mandateId)
            .set(CONTRACTS.SOURCE, source)
            .set(CONTRACTS.STATUS, status);
    if ("confirmed".equals(source)) {
      step = step.set(CONTRACTS.CONFIRMED_AT, OffsetDateTime.now())
          .set(CONTRACTS.HIVEMEM_CELL_ID, "cell-" + mandateId)
          .set(CONTRACTS.NOTES, "notes-" + mandateId);
    }
    return step.returning(CONTRACTS.ID).fetchOne(CONTRACTS.ID);
  }

  private long insertRecurring(
      long counterpartyId, Long contractId, String cadence, String typicalAmount, String source) {
    return db.insertInto(RECURRING)
        .set(RECURRING.COUNTERPARTY_ID, counterpartyId)
        .set(RECURRING.CONTRACT_ID, contractId)
        .set(RECURRING.CADENCE, cadence)
        .set(RECURRING.TYPICAL_AMOUNT, new BigDecimal(typicalAmount))
        .set(RECURRING.AMOUNT_MIN, new BigDecimal(typicalAmount))
        .set(RECURRING.AMOUNT_MAX, new BigDecimal(typicalAmount))
        .set(RECURRING.SOURCE, source)
        .returning(RECURRING.ID)
        .fetchOne(RECURRING.ID);
  }

  private void insertTxn(
      long importId,
      String contentHash,
      LocalDate bookingDate,
      String amount,
      String creditorId,
      String iban,
      String name,
      String mandateId) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal(amount))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.COUNTERPARTY_IBAN, iban)
        .set(TRANSACTIONS.COUNTERPARTY_NAME, name)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  private Long mergedInto(long counterpartyId) {
    return db.select(COUNTERPARTIES.MERGED_INTO)
        .from(COUNTERPARTIES)
        .where(COUNTERPARTIES.ID.eq(counterpartyId))
        .fetchOne(COUNTERPARTIES.MERGED_INTO);
  }

  private Integer evidenceTxnCount(long counterpartyId) {
    var row =
        db.fetchOne(
            "SELECT txn_count FROM v_counterparty_evidence WHERE counterparty_id = ?",
            counterpartyId);
    return row == null ? null : row.get("txn_count", Integer.class);
  }

  // --- tests ---

  @Test
  void mergePoolsBookingsAcrossEvidenceAndDrilldown() {
    long imp = importId();
    // Three fragments of the same real-world service: creditor-id, IBAN, name.
    insertTxn(imp, "t1", LocalDate.of(2026, 1, 1), "9.99", "SYNTH-CID", null, null, null);
    insertTxn(imp, "t2", LocalDate.of(2026, 2, 1), "9.99", null, "SYNTH-IBAN", null, null);
    insertTxn(imp, "t3", LocalDate.of(2026, 3, 1), "9.99", null, null, "Synth Provider", null);
    counterpartyResolver.resolve();

    long target =
        db.fetchOne(
                "SELECT id FROM counterparties WHERE identity_type='creditor_id' AND identity_value='SYNTH-CID'")
            .get("id", Long.class);
    long ibanSource =
        db.fetchOne(
                "SELECT id FROM counterparties WHERE identity_type='iban' AND identity_value='SYNTH-IBAN'")
            .get("id", Long.class);
    long nameSource =
        db.fetchOne(
                "SELECT id FROM counterparties WHERE identity_type='name' AND identity_value='SYNTH PROVIDER'")
            .get("id", Long.class);

    writeTools.mergeCounterparty(target, List.of(ibanSource, nameSource), "same provider, three identities");

    assertThat(evidenceTxnCount(target)).isEqualTo(3);
    List<TransactionView> drilldown = readTools.counterpartyTransactions(target, null, null, null);
    assertThat(drilldown).hasSize(3);

    List<CounterpartySummary> listed = readTools.listCounterparties(null, null);
    assertThat(listed.stream().map(CounterpartySummary::id))
        .contains(target)
        .doesNotContain(ibanSource, nameSource);
  }

  @Test
  void reimportOfFoldedVariantCreatesNoRecord() {
    long imp = importId();
    insertTxn(imp, "r1", LocalDate.of(2026, 1, 1), "5.00", "SYNTH-A", null, null, null);
    insertTxn(imp, "r2", LocalDate.of(2026, 1, 5), "5.00", null, "SYNTH-B-IBAN", null, null);
    counterpartyResolver.resolve();

    long target =
        db.fetchOne(
                "SELECT id FROM counterparties WHERE identity_type='creditor_id' AND identity_value='SYNTH-A'")
            .get("id", Long.class);
    long source =
        db.fetchOne(
                "SELECT id FROM counterparties WHERE identity_type='iban' AND identity_value='SYNTH-B-IBAN'")
            .get("id", Long.class);
    writeTools.mergeCounterparty(target, List.of(source), "same provider");

    // Re-import of a folded identity variant.
    insertTxn(imp, "r3", LocalDate.of(2026, 2, 1), "5.00", null, "SYNTH-B-IBAN", null, null);
    counterpartyResolver.resolve();

    Integer counterpartyCount =
        db.fetchOne(
                "SELECT count(*) c FROM counterparties WHERE identity_type='iban' AND identity_value='SYNTH-B-IBAN' AND merged_into IS NULL")
            .get("c", Integer.class);
    assertThat(counterpartyCount).isZero();
    assertThat(evidenceTxnCount(target)).isEqualTo(3);
  }

  @Test
  void resolverRerunAfterMergeNoGhostRows() {
    long imp = importId();
    // Two attributed-name fragments sharing one contract grain after merge (round-2 C1 regression).
    for (int month = 1; month <= 2; month++) {
      insertAttributed(imp, "a" + month, LocalDate.of(2026, month, 5), "SYNTH-ADYEN", "M-1", "Fizz Media");
    }
    counterpartyResolver.resolve();
    contractResolver.resolve();

    long target =
        db.fetchOne("SELECT id FROM counterparties WHERE identity_type='name' AND identity_value='FIZZ MEDIA'")
            .get("id", Long.class);

    // A second, independently-fragmented "Fizz Media" identity (simulated via a manual alias
    // target after being folded) -- create a genuine second counterparty via a distinct raw
    // identity (name variant) and merge it in.
    long source = insertCounterparty("name", "FIZZ MEDIA GMBH");
    writeTools.mergeCounterparty(target, List.of(source), "same merchant, name variant");

    // Resolver re-run must not error (no ON CONFLICT "affect row a second time") and must not
    // resurrect the folded source.
    counterpartyResolver.resolve();
    contractResolver.resolve();

    assertThat(mergedInto(source)).isEqualTo(target);
    Integer recurringRows =
        db.fetchOne("SELECT count(*) c FROM recurring WHERE counterparty_id = ?", source)
            .get("c", Integer.class);
    assertThat(recurringRows).isZero();
  }

  private void insertAttributed(
      long importId, String contentHash, LocalDate bookingDate, String creditorId, String mandateId, String attributedName) {
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, importId)
        .set(TRANSACTIONS.BOOKING_DATE, bookingDate)
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("9.99"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, creditorId)
        .set(TRANSACTIONS.MANDATE_ID, mandateId)
        .set(TRANSACTIONS.ATTRIBUTED_NAME, attributedName)
        .set(TRANSACTIONS.ATTRIBUTION_SOURCE, "paypal")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void contractlessConfirmedRecurringSurvives() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-1");
    long sourceConfirmed = insertCounterparty("creditor_id", "SYNTH-SRC-CONF");
    long sourceAuto = insertCounterparty("creditor_id", "SYNTH-SRC-AUTO");
    insertRecurring(sourceConfirmed, null, "monthly", "12.00", "confirmed");
    insertRecurring(sourceAuto, null, "monthly", "7.50", "auto");

    writeTools.mergeCounterparty(target, List.of(sourceConfirmed, sourceAuto), "consolidate");

    // Only one contract-less recurring row can exist per counterparty (NULLS NOT DISTINCT), so
    // the second source's mandate-less series collides with the first and is dropped/upgraded --
    // either way, something under the target must survive (never silently lost). It must
    // specifically be the human-confirmed series (cadence/amount), never the auto one.
    var targetRow =
        db.selectFrom(RECURRING)
            .where(RECURRING.COUNTERPARTY_ID.eq(target))
            .and(RECURRING.CONTRACT_ID.isNull())
            .fetchOne();
    assertThat(targetRow).isNotNull();
    assertThat(targetRow.getSource()).isEqualTo("confirmed");
    assertThat(targetRow.getCadence()).isEqualTo("monthly");
    assertThat(targetRow.getTypicalAmount()).isEqualByComparingTo("12.00");
    Integer sourceRowsRemaining =
        db.fetchOne(
                "SELECT count(*) c FROM recurring WHERE counterparty_id IN (?, ?)",
                sourceConfirmed,
                sourceAuto)
            .get("c", Integer.class);
    assertThat(sourceRowsRemaining).isZero();
  }

  @Test
  void confirmedContractPreservedAndUpgradesAutoTarget() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-2");
    long targetContract = insertContract(target, "M-SHARED", "auto", "open");
    insertRecurring(target, targetContract, "irregular", "5.00", "auto");

    long source = insertCounterparty("creditor_id", "SYNTH-SRC-2");
    long sourceContract = insertContract(source, "M-SHARED", "confirmed", "confirmed");
    insertRecurring(source, sourceContract, "monthly", "19.99", "confirmed");

    writeTools.mergeCounterparty(target, List.of(source), "same mandate");

    var contractRow =
        db.selectFrom(CONTRACTS)
            .where(CONTRACTS.COUNTERPARTY_ID.eq(target))
            .and(CONTRACTS.MANDATE_ID.eq("M-SHARED"))
            .fetchOne();
    assertThat(contractRow).isNotNull();
    assertThat(contractRow.getSource()).isEqualTo("confirmed");
    assertThat(contractRow.getStatus()).isEqualTo("confirmed");
    assertThat(contractRow.getHivememCellId()).isEqualTo("cell-M-SHARED");

    var recurringRow =
        db.selectFrom(RECURRING).where(RECURRING.CONTRACT_ID.eq(contractRow.getId())).fetchOne();
    assertThat(recurringRow.getSource()).isEqualTo("confirmed");
    assertThat(recurringRow.getCadence()).isEqualTo("monthly");
    assertThat(recurringRow.getTypicalAmount()).isEqualByComparingTo("19.99");

    ObligationsRegister register = readTools.obligationsRegister();
    assertThat(register.rows().stream().anyMatch(c -> c.contractId() == contractRow.getId()))
        .isTrue();
  }

  @Test
  void collisionOrderingIncludingNullMandate() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-3");
    long source1 = insertCounterparty("creditor_id", "SYNTH-SRC-3A");
    long source2 = insertCounterparty("creditor_id", "SYNTH-SRC-3B");
    insertContract(source1, "M-DUP", "confirmed", "confirmed");
    insertContract(source2, "M-DUP", "confirmed", "confirmed");
    // NULL-mandate pair too (materialized contracts, e.g. via confirm_counterparty).
    insertContract(source1, null, "confirmed", "confirmed");
    insertContract(source2, null, "confirmed", "confirmed");

    writeTools.mergeCounterparty(target, List.of(source1, source2), "two fragments, same mandate");

    Integer dupMandateContracts =
        db.fetchOne(
                "SELECT count(*) c FROM contracts WHERE counterparty_id = ? AND mandate_id = 'M-DUP'",
                target)
            .get("c", Integer.class);
    assertThat(dupMandateContracts).isEqualTo(1);
    Integer nullMandateContracts =
        db.fetchOne(
                "SELECT count(*) c FROM contracts WHERE counterparty_id = ? AND mandate_id IS NULL",
                target)
            .get("c", Integer.class);
    assertThat(nullMandateContracts).isEqualTo(1);
  }

  @Test
  void confirmedTagNotDowngraded() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-4");
    insertTag(target, "domain", "versicherung", "confirmed");
    long source = insertCounterparty("creditor_id", "SYNTH-SRC-4");
    insertTag(source, "domain", "versicherung", "auto");

    writeTools.mergeCounterparty(target, List.of(source), "consolidate tags");

    String resultSource =
        db.fetchOne(
                "SELECT source FROM counterparty_tags WHERE counterparty_id = ? AND dimension='domain' AND value='versicherung'",
                target)
            .get("source", String.class);
    assertThat(resultSource).isEqualTo("confirmed");
  }

  @Test
  void transitiveAliasAndChainFlatten() {
    long grandTarget = insertCounterparty("creditor_id", "SYNTH-GT");
    long middle = insertCounterparty("creditor_id", "SYNTH-MID");
    long leaf = insertCounterparty("creditor_id", "SYNTH-LEAF");
    // leaf aliases + chains into middle first.
    writeTools.mergeCounterparty(middle, List.of(leaf), "leaf into middle");
    // then middle (carrying leaf's chain) merges into grandTarget.
    writeTools.mergeCounterparty(grandTarget, List.of(middle), "middle into grand target");

    assertThat(mergedInto(leaf)).isEqualTo(grandTarget);
    assertThat(mergedInto(middle)).isEqualTo(grandTarget);
    Long leafAliasCanonical =
        db.select(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID)
            .from(COUNTERPARTY_ALIAS)
            .where(COUNTERPARTY_ALIAS.IDENTITY_TYPE.eq("creditor_id"))
            .and(COUNTERPARTY_ALIAS.IDENTITY_VALUE.eq("SYNTH-LEAF"))
            .fetchOne(COUNTERPARTY_ALIAS.CANONICAL_COUNTERPARTY_ID);
    assertThat(leafAliasCanonical).isEqualTo(grandTarget);
  }

  @Test
  void idempotentRemergeAndDifferentTargetError() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-5");
    long source = insertCounterparty("creditor_id", "SYNTH-SRC-5");
    writeTools.mergeCounterparty(target, List.of(source), "first merge");

    BatchWriteAck again = writeTools.mergeCounterparty(target, List.of(source), "repeat");
    assertThat(again.affectedCount()).isZero();

    long otherTarget = insertCounterparty("creditor_id", "SYNTH-OTHER-5");
    assertThatThrownBy(() -> writeTools.mergeCounterparty(otherTarget, List.of(source), "wrong target"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already been merged");
  }

  @Test
  void guardsRejectInactiveOrSelfOrEmpty() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-6");
    long alreadyFolded = insertCounterparty("creditor_id", "SYNTH-FOLDED-6");
    long other = insertCounterparty("creditor_id", "SYNTH-OTHER-6");
    writeTools.mergeCounterparty(other, List.of(alreadyFolded), "seed a folded source");

    assertThatThrownBy(() -> writeTools.mergeCounterparty(target, List.of(), "empty"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-empty");

    assertThatThrownBy(() -> writeTools.mergeCounterparty(target, List.of(target), "self"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itself");

    assertThatThrownBy(() -> writeTools.mergeCounterparty(alreadyFolded, List.of(other), "inactive target"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merged into");

    long dup = insertCounterparty("creditor_id", "SYNTH-DUP-6");
    assertThatThrownBy(() -> writeTools.mergeCounterparty(target, List.of(dup, dup), "duplicate"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicates");
  }

  @Test
  void mergeCommitsDespiteFailingTagRule() {
    long target = insertCounterparty("creditor_id", "SYNTH-TARGET-7");
    long source = insertCounterparty("creditor_id", "SYNTH-SRC-7");
    // A rule inserted directly (bypassing create_tag_rule's validation) with a CHECK-violating
    // action dimension -- TagRuleResolver applies each rule in its own transaction and swallows a
    // per-rule failure, but this still exercises the tx-boundary guarantee: the merge's own core
    // transaction has already committed before resolvers even run, so a resolver-settle problem
    // of any kind cannot roll the merge back.
    db.execute(
        "INSERT INTO tag_rules (name, conditions, actions) VALUES ('broken', "
            + "'[{\"field\":\"counterparty_name\",\"op\":\"contains\",\"value\":\"x\"}]'::jsonb, "
            + "'[{\"dimension\":\"not_a_real_dimension\",\"value\":\"x\"}]'::jsonb)");

    BatchWriteAck ack = writeTools.mergeCounterparty(target, List.of(source), "despite broken rule");

    assertThat(ack.affectedCount()).isEqualTo(1);
    assertThat(mergedInto(source)).isEqualTo(target);
  }
}
