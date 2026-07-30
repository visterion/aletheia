package de.visterion.aletheia.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins that {@code ProductSplitResolver} runs on the ingest trigger, between counterparty and
 * contract resolution (spec §5).
 *
 * <p>Without the wiring the resolver only ever runs at startup: an upload would land, contracts
 * would be derived from the unsplit lump, and the products would appear silently late -- on the next
 * container restart. That gap is invisible in a suite that calls the resolvers by hand, which is
 * why this test drives the real {@link IngestEndpointService} pipeline instead.
 *
 * <p>Fixtures are hand-invented ({@code CDTR-INSURER}, {@code POLICY-1}, {@code Health}/{@code
 * Legal}); no production identifier exists in this repository.
 */
class ProductRuleIngestWiringIT extends AbstractPostgresIT {

  private static final String PATTERN =
      "(?<product>\\p{L}+)\\s+(?:(?<policy>[A-Z0-9.-]+)\\s+)?(?<amount>[0-9.]*[0-9],[0-9]{2})";

  /** Two bookings in two calendar months, each bundling two products under one mandate. */
  private static final String BOOKINGS_JSON =
      "[{\"Id\":\"1\",\"OwnrAcctIBAN\":\"DE00000000000000000001\",\"Amt\":\"150.00\","
          + "\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-01-15\","
          + "\"BookgSts\":\"BOOK\",\"RmtdNm\":\"SYNTHETIC INSURER\",\"CdtrId\":\"CDTR-INSURER\","
          + "\"MndtId\":\"POLICY-1\",\"RmtInf\":\"POLICY-1 Health 100,00 Legal 50,00\"},"
          + "{\"Id\":\"2\",\"OwnrAcctIBAN\":\"DE00000000000000000001\",\"Amt\":\"150.00\","
          + "\"AmtCcy\":\"EUR\",\"CdtDbtInd\":\"DBIT\",\"BookgDt\":\"2026-02-15\","
          + "\"BookgSts\":\"BOOK\",\"RmtdNm\":\"SYNTHETIC INSURER\",\"CdtrId\":\"CDTR-INSURER\","
          + "\"MndtId\":\"POLICY-1\",\"RmtInf\":\"POLICY-1 Health 100,00 Legal 50,00\"}]";

  @Autowired IngestEndpointService service;
  @Autowired IngestProperties properties;
  @Autowired DSLContext db;

  @AfterEach
  void cleanUp() throws IOException {
    db.execute(
        "TRUNCATE TABLE recurring, contracts, counterparty_tags, counterparty_history,"
            + " transactions, imports, tag_rules, product_rules, counterparties"
            + " RESTART IDENTITY CASCADE");
    deleteRecursively(properties.dir().resolve("incoming"));
    deleteRecursively(properties.dir().resolve("imported"));
  }

  @Test
  void uploadSplitsByProductAndDerivesProductContractsWithoutRestart() {
    db.execute(
        "INSERT INTO product_rules (creditor_id, position_pattern) VALUES ('CDTR-INSURER', ?)",
        PATTERN);

    service.ingestUpload(BOOKINGS_JSON.getBytes(UTF_8), "giro.json");

    List<String> childProducts =
        db.fetch(
                "SELECT product FROM transactions WHERE split_parent_content_hash IS NOT NULL"
                    + " ORDER BY product")
            .map(r -> r.get("product", String.class));
    assertThat(childProducts).containsExactly("HEALTH", "HEALTH", "LEGAL", "LEGAL");

    // The contract layer saw the children in the SAME pass: product resolution must run before
    // contract resolution, not merely at some later point.
    List<String> products =
        db.fetch("SELECT product FROM contracts ORDER BY product NULLS LAST")
            .map(r -> r.get("product", String.class));
    assertThat(products).containsExactly("HEALTH", "LEGAL");
  }

  @Test
  void uploadWithoutRuleKeepsTodaysLumpBehaviour() {
    service.ingestUpload(BOOKINGS_JSON.getBytes(UTF_8), "giro.json");

    assertThat(
            db.fetchOne(
                    "SELECT count(*) AS n FROM transactions"
                        + " WHERE split_parent_content_hash IS NOT NULL")
                .get("n", Integer.class))
        .isZero();
    List<String> products =
        db.fetch("SELECT product FROM contracts").map(r -> r.get("product", String.class));
    assertThat(products).containsExactly((String) null);
  }

  private static void deleteRecursively(java.nio.file.Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(Comparator.reverseOrder())
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
