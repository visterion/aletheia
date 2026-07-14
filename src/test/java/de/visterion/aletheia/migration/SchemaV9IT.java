package de.visterion.aletheia.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaV9IT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private long insertCounterparty(String creditorId) {
    return db.insertInto(org.jooq.impl.DSL.table("counterparties"))
        .set(org.jooq.impl.DSL.field("identity_type"), "creditor_id")
        .set(org.jooq.impl.DSL.field("identity_value"), creditorId)
        .set(org.jooq.impl.DSL.field("display_name"), "X")
        .returning(org.jooq.impl.DSL.field("id", Long.class))
        .fetchOne()
        .get("id", Long.class);
  }

  @Test
  void contracts_mandateId_is_nullable_and_unique_nulls_not_distinct() {
    long cp = insertCounterparty("DE-CR-1");
    // two rows, same counterparty, distinct mandates -> allowed
    insertContract(cp, "M1");
    insertContract(cp, "M2");
    // duplicate (counterparty, mandate) -> rejected
    assertThatThrownBy(() -> insertContract(cp, "M1"))
        .isInstanceOf(IntegrityConstraintViolationException.class);
    // one NULL-mandate row allowed
    insertContract(cp, null);
    // a second NULL-mandate row for the same counterparty -> rejected (NULLS NOT DISTINCT)
    assertThatThrownBy(() -> insertContract(cp, null))
        .isInstanceOf(IntegrityConstraintViolationException.class);
  }

  private void insertContract(long cp, String mandate) {
    db.insertInto(org.jooq.impl.DSL.table("contracts"))
        .set(org.jooq.impl.DSL.field("counterparty_id"), cp)
        .set(org.jooq.impl.DSL.field("mandate_id"), mandate)
        .execute();
  }

  @Test
  void recurring_has_nullable_contract_id_and_new_unique() {
    Integer col =
        db.selectCount()
            .from("information_schema.columns")
            .where("table_name = 'recurring' and column_name = 'contract_id'")
            .fetchOne(0, Integer.class);
    assertThat(col).isEqualTo(1);

    Integer oldConstraint =
        db.selectCount()
            .from("information_schema.table_constraints")
            .where("constraint_name = 'uq_recurring_counterparty'")
            .fetchOne(0, Integer.class);
    assertThat(oldConstraint).isZero();
  }

  @Test
  void v_contract_evidence_exists() {
    Integer v =
        db.selectCount()
            .from("information_schema.views")
            .where("table_name = 'v_contract_evidence'")
            .fetchOne(0, Integer.class);
    assertThat(v).isEqualTo(1);
  }
}
