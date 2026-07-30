package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.CounterpartyEvidence;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the wire shape of {@code get_review_queue} rows (spec §8d): null-valued fields are omitted
 * entirely. Uses the Spring-injected ObjectMapper -- the same bean ToolCallDispatcher serializes
 * tool results with -- so the test cannot pass against a differently configured mapper.
 *
 * <p>Assertions run against the parsed root object, never against the raw JSON string: {@code
 * RecurringView} carries its own nested {@code cadence} and {@code lastSeen} components, so a
 * substring match would silently pass even if the top-level field were dropped.
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
        null,
        evidence,
        recurring,
        new BigDecimal("120.00"),
        recurring == null ? null : recurring.cadence(),
        txnCount,
        lastSeen);
  }

  private JsonNode serialize(ReviewQueueEntry entry) {
    return mapper.readTree(mapper.writeValueAsString(entry));
  }

  @Test
  void compactRowOmitsEvidenceAndRecurring() {
    JsonNode root = serialize(entry(7L, null, null, 12, LocalDate.now()));

    assertThat(root.has("evidence")).isFalse();
    assertThat(root.has("recurring")).isFalse();
    assertThat(root.has("annualCostEstimate")).isTrue();
    assertThat(root.has("txnCount")).isTrue();
  }

  @Test
  void verboseRowWithoutARecurringSeriesOmitsRecurringAndCadence() {
    JsonNode root = serialize(entry(7L, null, null, 12, LocalDate.now()));

    assertThat(root.has("recurring")).isFalse();
    assertThat(root.has("cadence")).isFalse();
  }

  @Test
  void verboseRowWithARecurringSeriesKeepsRecurringAndCadence() {
    JsonNode root = serialize(entry(7L, null, SERIES, 12, LocalDate.now()));

    assertThat(root.has("recurring")).isTrue();
    assertThat(root.get("recurring").isNull()).isFalse();
    assertThat(root.has("cadence")).isTrue();
    assertThat(root.get("cadence").stringValue()).isEqualTo("monthly");
    // A populated series must not mask the omission itself: evidence is still null on this row.
    assertThat(root.has("evidence")).isFalse();
  }

  @Test
  void mandateLessRowOmitsContractId() {
    JsonNode root = serialize(entry(null, null, null, 12, LocalDate.now()));

    assertThat(root.has("contractId")).isFalse();
  }

  @Test
  void rowWithoutAnEvidenceRowOmitsTxnCountAndLastSeen() {
    JsonNode root = serialize(entry(7L, null, null, null, null));

    assertThat(root.has("txnCount")).isFalse();
    assertThat(root.has("lastSeen")).isFalse();
  }
}
