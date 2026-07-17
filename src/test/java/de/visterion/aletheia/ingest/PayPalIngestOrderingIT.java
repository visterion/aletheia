package de.visterion.aletheia.ingest;

import static de.visterion.aletheia.jooq.Tables.COUNTERPARTIES;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "aletheia.paypal.creditor-ids=SYNTH-PP-CREDITOR")
class PayPalIngestOrderingIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired IngestEndpointService endpoint;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  @Test
  void uploadedPaypalBookingResolvesToMerchantCounterparty() {
    // One synthetic Subsembly booking with the PayPal creditor id and a parseable remittance.
    // Field names match SubsemblyParser/SubsemblyBooking (see synthetic-basic.json fixture).
    String export =
        """
        [
          {
            "Id": "9001", "AcctId": "SYNTH-ACC", "OwnrAcctIBAN": "DE00000000000000000001",
            "Amt": "10.00", "AmtCcy": "EUR", "CdtDbtInd": "DBIT",
            "BookgDt": "2026-01-01", "ValDt": "2026-01-01", "BookgSts": "BOOK",
            "BookgTxt": "SEPA", "RmtInf": ". Fizz Media, Ihr Einkauf bei Fizz Media",
            "RmtdNm": "PayPal Europe S.a.r.l.", "CdtrId": "SYNTH-PP-CREDITOR",
            "MndtId": "MND-9001", "EndToEndId": "E2E-9001"
          }
        ]
        """;

    endpoint.ingestUpload(export.getBytes(StandardCharsets.UTF_8), "synthetic-paypal.json");

    var rows =
        db.select(COUNTERPARTIES.IDENTITY_TYPE, COUNTERPARTIES.IDENTITY_VALUE)
            .from(COUNTERPARTIES)
            .fetch();
    assertThat(rows)
        .extracting(
            r -> r.get(COUNTERPARTIES.IDENTITY_TYPE), r -> r.get(COUNTERPARTIES.IDENTITY_VALUE))
        .contains(org.assertj.core.groups.Tuple.tuple("name", "FIZZ MEDIA"));
  }
}
