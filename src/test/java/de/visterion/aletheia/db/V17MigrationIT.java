package de.visterion.aletheia.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class V17MigrationIT extends AbstractPostgresIT {

  @Autowired DSLContext db;

  @Test
  void seedsFiveRoleMappings() {
    Integer count = db.fetchCount(db.selectFrom("cashflow_role_map"));
    assertThat(count).isEqualTo(5);
    String role =
        db.fetchOne(
                "SELECT role FROM cashflow_role_map WHERE dimension = 'nature' AND value = 'investment'")
            .get("role", String.class);
    assertThat(role).isEqualTo("depot");
  }

  @Test
  void roleCheckRejectsUnknownRole() {
    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO cashflow_role_map (dimension, value, role) "
                        + "VALUES ('domain', 'bogusval', 'not_a_role')"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void dimensionCheckRejectsUnknownDimension() {
    assertThatThrownBy(
            () ->
                db.execute(
                    "INSERT INTO cashflow_role_map (dimension, value, role) "
                        + "VALUES ('necessity', 'pflicht', 'income')"))
        .isInstanceOf(DataAccessException.class);
  }
}
