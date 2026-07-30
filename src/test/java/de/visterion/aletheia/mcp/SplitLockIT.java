package de.visterion.aletheia.mcp;

import static de.visterion.aletheia.jooq.Tables.IMPORTS;
import static de.visterion.aletheia.jooq.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.ingest.AbstractPostgresIT;
import de.visterion.aletheia.substrate.SubstrateLock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves that {@code split_transaction} runs under {@link SubstrateLock}.
 *
 * <p>There is no pre-existing lock assertion in this suite to mirror ({@code
 * ReattributeTransactionIT} and the merge ITs assert outcomes, never the lock), so this test asserts
 * the only externally observable property the lock has: a caller cannot enter {@code
 * splitTransaction} while another thread holds the lock, and proceeds once it is released. Without
 * the lock the call completes immediately and the first assertion fails.
 *
 * <p>Motivation: {@code ProductSplitResolver} derives the same {@code syntheticSplitHash(parentHash,
 * i)} child keys, so tool and resolver can collide on {@code uq_transactions_natural_key}.
 */
class SplitLockIT extends AbstractPostgresIT {

  @Autowired DSLContext db;
  @Autowired WriteTools writeTools;
  @Autowired SubstrateLock substrateLock;

  @AfterEach
  void cleanUp() {
    db.execute(
        "TRUNCATE TABLE counterparty_history, contracts, recurring, counterparty_tags, "
            + "counterparties RESTART IDENTITY CASCADE");
    db.execute("TRUNCATE TABLE transactions, imports RESTART IDENTITY CASCADE");
  }

  private void seedParentTx(String contentHash) {
    long imp =
        db.insertInto(IMPORTS)
            .set(IMPORTS.FILE_NAME, "synthetic.json")
            .set(IMPORTS.FILE_SHA256, "sha-" + UUID.randomUUID())
            .returning(IMPORTS.ID)
            .fetchOne(IMPORTS.ID);
    db.insertInto(TRANSACTIONS)
        .set(TRANSACTIONS.CONTENT_HASH, contentHash)
        .set(TRANSACTIONS.OCCURRENCE_INDEX, 0)
        .set(TRANSACTIONS.IMPORT_ID, imp)
        .set(TRANSACTIONS.BOOKING_DATE, LocalDate.now().minusDays(1))
        .set(TRANSACTIONS.AMOUNT, new BigDecimal("100.00"))
        .set(TRANSACTIONS.CURRENCY, "EUR")
        .set(TRANSACTIONS.DIRECTION, "DBIT")
        .set(TRANSACTIONS.BOOKING_STATUS, "BOOK")
        .set(TRANSACTIONS.CREDITOR_ID, "CDTR-INSURER")
        .set(TRANSACTIONS.MANDATE_ID, "MND-1")
        .set(TRANSACTIONS.COUNTERPARTY_NAME, "SYNTHETIC INSURER")
        .set(TRANSACTIONS.RAW, JSONB.valueOf("{}"))
        .execute();
  }

  @Test
  void splitTransactionBlocksWhileTheSubstrateLockIsHeldAndProceedsAfterRelease()
      throws Exception {
    String parentHash = "split-lock-parent";
    seedParentTx(parentHash);

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    substrateLock.lock();
    Thread splitter =
        new Thread(
            () -> {
              started.countDown();
              try {
                writeTools.splitTransaction(
                    new TxReference(parentHash, 0),
                    List.of(
                        new Allocation(
                            null, "SYNTHETIC INSURER", null, new BigDecimal("60.00"), "POLICY-1"),
                        new Allocation(
                            null, "Bargeld", null, new BigDecimal("40.00"), "POLICY-1 rest")),
                    null);
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                finished.countDown();
              }
            },
            "split-lock-it");
    try {
      splitter.start();
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

      // The lock is held here, so the split must not be able to complete.
      assertThat(finished.await(1, TimeUnit.SECONDS))
          .as("split_transaction completed while SubstrateLock was held -- it is not locked")
          .isFalse();
    } finally {
      substrateLock.unlock();
    }

    assertThat(finished.await(30, TimeUnit.SECONDS))
        .as("split_transaction did not complete after the lock was released")
        .isTrue();
    splitter.join();
    assertThat(failure.get()).isNull();

    assertThat(
            db.fetchCount(TRANSACTIONS, TRANSACTIONS.SPLIT_PARENT_CONTENT_HASH.eq(parentHash)))
        .isEqualTo(2);
  }
}
