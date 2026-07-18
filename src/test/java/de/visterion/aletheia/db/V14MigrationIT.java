package de.visterion.aletheia.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class V14MigrationIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  @Test
  void insertsAValidRule() {
    db.execute(
        "INSERT INTO tag_rules (name, conditions, actions) VALUES "
            + "('telekom', '[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"x\"}]'::jsonb, "
            + "'[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]'::jsonb)");
    assertThat(db.fetchCount(db.selectFrom("tag_rules"))).isEqualTo(1);
  }

  @Test
  void rejectsEmptyConditionsArray() {
    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO tag_rules (name, conditions, actions) VALUES "
                        + "('bad', '[]'::jsonb, "
                        + "'[{\"dimension\":\"domain\",\"value\":\"x\"}]'::jsonb)"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void rejectsBlankName() {
    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO tag_rules (name, conditions, actions) VALUES "
                        + "('   ', '[{\"field\":\"direction\",\"op\":\"equals\",\"value\":\"DBIT\"}]'::jsonb, "
                        + "'[{\"dimension\":\"domain\",\"value\":\"x\"}]'::jsonb)"))
        .isInstanceOf(DataAccessException.class);
  }
}
