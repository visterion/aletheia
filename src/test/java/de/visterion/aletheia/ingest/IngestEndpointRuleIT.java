package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IngestEndpointRuleIT extends AbstractPostgresIT {

  private static final String BOOKING_JSON =
      "[{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DEACC\",\"Amt\":\"10.00\",\"AmtCcy\":\"EUR\","
          + "\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-08-01\",\"BookgSts\":\"BOOK\","
          + "\"RmtdNm\":\"ACME\",\"RmtInf\":\"TELEKOM MONTHLY\"}]";

  @Autowired IngestEndpointService service;
  @Autowired IngestProperties properties;
  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() throws IOException {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history, "
            + "transactions, imports, tag_rules, counterparties RESTART IDENTITY CASCADE");
    deleteRecursively(properties.dir().resolve("incoming"));
    deleteRecursively(properties.dir().resolve("imported"));
  }

  @Test
  void enabledRuleTagsUploadedCounterparty() {
    db.execute(
        "INSERT INTO tag_rules (name, conditions, actions) VALUES ('telekom', "
            + "'[{\"field\":\"remittance_info\",\"op\":\"contains\",\"value\":\"telekom\"}]'::jsonb, "
            + "'[{\"dimension\":\"domain\",\"value\":\"telekommunikation\"}]'::jsonb)");

    service.ingestUpload(BOOKING_JSON.getBytes(UTF_8), "giro.json");

    long cp = (long) db.fetchValue("SELECT id FROM counterparties WHERE identity_value='ACME'");
    assertThat(
            db.fetchValue(
                "SELECT value FROM counterparty_tags WHERE counterparty_id=? AND dimension='domain'", cp))
        .isEqualTo("telekommunikation");
  }

  @Test
  void zeroRulesLeaveNoTags() {
    service.ingestUpload(BOOKING_JSON.getBytes(UTF_8), "giro.json");
    assertThat(db.fetchCount(db.selectFrom("counterparty_tags"))).isZero();
  }

  private static void deleteRecursively(java.nio.file.Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
    }
  }
}
