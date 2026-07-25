package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the wire shape of {@code get_review_queue} rows (spec §8d): null-valued fields are omitted
 * entirely. Uses the Spring-injected ObjectMapper -- the same bean ToolCallDispatcher serializes
 * tool results with -- so the test cannot pass against a differently configured mapper.
 */
class ReviewQueueSerializationIT extends AbstractPostgresIT {

  @Autowired ObjectMapper mapper;

  private static final RecurringView SERIES =
      new RecurringView(
          1L,
          "monthly",
          new BigDecimal("10.00"),
          new BigDecimal("10.00"),
          new BigDecimal("10.00"),
          LocalDate.now().minusMonths(3),
          LocalDate.now(),
          3,
          "confirmed",
          new BigDecimal("0.90"));

  private ReviewQueueEntry entry(
      Long contractId,
      CounterpartyEvidence evidence,
      RecurringView recurring,
      Integer txnCount,
      LocalDate lastSeen) {
    return new ReviewQueueEntry(
        1L,
        "Telco One",
        "creditor_id",
        contractId,
        evidence,
        recurring,
        new BigDecimal("120.00"),
        recurring == null ? null : recurring.cadence(),
        txnCount,
        lastSeen);
  }

  @Test
  void compactRowOmitsEvidenceAndRecurring() {
    String json = mapper.writeValueAsString(entry(7L, null, null, 12, LocalDate.now()));

    assertThat(json).doesNotContain("\"evidence\"").doesNotContain("\"recurring\"");
    assertThat(json).contains("\"annualCostEstimate\"").contains("\"txnCount\"");
  }

  @Test
  void verboseRowWithoutARecurringSeriesOmitsRecurringAndCadence() {
    String json = mapper.writeValueAsString(entry(7L, null, null, 12, LocalDate.now()));

    assertThat(json).doesNotContain("\"recurring\"").doesNotContain("\"cadence\"");
  }

  @Test
  void verboseRowWithARecurringSeriesKeepsRecurringAndCadence() {
    String json = mapper.writeValueAsString(entry(7L, null, SERIES, 12, LocalDate.now()));

    assertThat(json).contains("\"recurring\"").contains("\"cadence\"");
  }

  @Test
  void mandateLessRowOmitsContractId() {
    String json = mapper.writeValueAsString(entry(null, null, null, 12, LocalDate.now()));

    assertThat(json).doesNotContain("\"contractId\"");
  }

  @Test
  void rowWithoutAnEvidenceRowOmitsTxnCountAndLastSeen() {
    String json = mapper.writeValueAsString(entry(7L, null, null, null, null));

    assertThat(json).doesNotContain("\"txnCount\"").doesNotContain("\"lastSeen\"");
  }
}
