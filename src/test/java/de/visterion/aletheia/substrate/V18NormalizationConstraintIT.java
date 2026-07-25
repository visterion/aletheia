package de.visterion.aletheia.substrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers {@code V18__normalization_constraints.sql}: the four CHECK constraints that pin the
 * counterparty-name normalization rule in the database, and the preflight that has to diagnose an
 * identity collision before the backfill can trip over it. The preflight covers seven shapes behind
 * six guards, labelled 1, 2, 3, 4, 6, 7 in the migration; "case 5" names the exemption inside case
 * 4 rather than a guard of its own. See the migration's numbering legend.
 *
 * <p>One container is shared by every test; each test gets its own Postgres schema and its own
 * Flyway run into that schema, because the preflight cases must be seeded into a database migrated
 * only as far as V17 (before the constraints exist) and must then observe an independent migration
 * to V18. Per-schema isolation is used instead of the per-container idiom of {@code V7BackfillIT}
 * because a container start per scenario would be the expensive part, and no migration in this repo
 * schema-qualifies a single identifier.
 *
 * <p>All names are synthetic. Non-ASCII whitespace is constructed from codepoints, never written
 * literally, so an invisible character cannot be lost in an editor or diff.
 */
@Testcontainers
class V18NormalizationConstraintIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** U+2003. PostgreSQL's {@code \s} collapses it; see {@code NameNormalizationSqlIT}'s corpus. */
  private static final String EM_SPACE = String.valueOf((char) 0x2003);

  private static DataSource dataSource(String schema) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    ds.setCurrentSchema(schema);
    return ds;
  }

  /** Migrates {@code schema} to {@code target} ({@code null} = latest) and returns a jOOQ handle. */
  private static DSLContext migrate(String schema, String target) {
    DataSource ds = dataSource(schema);
    var config =
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas(schema);
    if (target != null) {
      config = config.target(MigrationVersion.fromVersion(target));
    }
    config.load().migrate();
    return DSL.using(ds, SQLDialect.POSTGRES);
  }

  /** Second phase of a preflight scenario: migrate a V17-seeded schema the rest of the way. */
  private static void migrateToV18(String schema) {
    migrate(schema, null);
  }

  private static long insertCounterparty(
      DSLContext db, String identityType, String identityValue, String displayName) {
    return db.fetchOne(
            "insert into counterparties(identity_type, identity_value, display_name)"
                + " values (?, ?, cast(? as text)) returning id",
            identityType,
            identityValue,
            displayName)
        .get(0, Long.class);
  }

  private static long insertTombstone(
      DSLContext db, String identityValue, String displayName, long mergedInto) {
    return db.fetchOne(
            "insert into counterparties(identity_type, identity_value, display_name, merged_into)"
                + " values ('name', ?, cast(? as text), ?) returning id",
            identityValue,
            displayName,
            mergedInto)
        .get(0, Long.class);
  }

  private static long insertAlias(DSLContext db, String identityValue, long canonicalId) {
    return db.fetchOne(
            "insert into counterparty_alias(identity_type, identity_value,"
                + " canonical_counterparty_id) values ('name', ?, ?) returning id",
            identityValue,
            canonicalId)
        .get(0, Long.class);
  }

  // -------------------------------------------------------------------------------------------
  // Group 1: constraint behaviour on a fully migrated schema.
  // -------------------------------------------------------------------------------------------

  @Test
  void constraintsRejectNonNormalizedAndAcceptEverythingElse() {
    DSLContext db = migrate("g1", null);
    long living = insertCounterparty(db, "name", "SYNTH LIVING ONE", "Synth Living One");

    // --- rejected ---

    assertThatThrownBy(
            () ->
                db.execute(
                    "update counterparties set display_name = ? where id = ?",
                    "  padded  name ",
                    living))
        .hasStackTraceContaining("counterparties_display_name_normalized");

    assertThatThrownBy(
            () ->
                db.execute(
                    "update counterparties set display_name_override = ? where id = ?",
                    "padded" + EM_SPACE + "name",
                    living))
        .hasStackTraceContaining("counterparties_display_name_override_normalized");

    assertThatThrownBy(
            () -> insertCounterparty(db, "name", "lower case value", "X"))
        .hasStackTraceContaining("counterparties_name_identity_normalized");

    assertThatThrownBy(() -> insertAlias(db, "lower case alias", living))
        .hasStackTraceContaining("counterparty_alias_name_identity_normalized");

    // --- accepted ---

    // Lower-cased, but not a 'name' identity: creditor_id/iban come from the export verbatim.
    long iban = insertCounterparty(db, "iban", "de00synth0000000001", "X");
    assertThat(iban).isPositive();

    db.execute(
        "update counterparties set display_name = null, display_name_override = null where id = ?",
        living);
    db.execute("update counterparties set display_name = '' where id = ?", living);
    assertThat(
            db.fetchOne("select display_name from counterparties where id = ?", living)
                .get(0, String.class))
        .isEmpty();

    // Tombstone exemption: a folded row keeps its raw identity, which stays inert but holds its
    // slot in uq_counterparty_identity.
    long tomb = insertTombstone(db, "SYNTH  TOMB ONE", null, living);
    assertThat(
            db.fetchOne("select identity_value from counterparties where id = ?", tomb)
                .get(0, String.class))
        .isEqualTo("SYNTH  TOMB ONE");
  }

  // -------------------------------------------------------------------------------------------
  // Group 2: the five-case preflight matrix. Seed at V17, then migrate to V18.
  // -------------------------------------------------------------------------------------------

  @Test
  void preflightCase1RejectsTwoDirtyCounterpartiesCollidingWithEachOther() {
    DSLContext db = migrate("g2case1", "17");
    long a = insertCounterparty(db, "name", "SYNTH  ALPHA", "Synth Alpha");
    long b = insertCounterparty(db, "name", "SYNTH   ALPHA", "Synth Alpha");

    // Asserted as one contiguous fragment, not as loose id substrings: a bare "1" would match
    // almost any diagnostic and would not prove this case fired rather than another.
    assertThatThrownBy(() -> migrateToV18("g2case1"))
        .hasStackTraceContaining("V18 preflight")
        .hasStackTraceContaining("counterparties " + a + " and " + b + " both normalize to");
  }

  @Test
  void preflightCase2RejectsDirtyCounterpartyLandingOnAnExistingCleanOne() {
    DSLContext db = migrate("g2case2", "17");
    long clean = insertCounterparty(db, "name", "SYNTH BETA", "Synth Beta");
    long dirty = insertCounterparty(db, "name", "SYNTH  BETA", "Synth Beta");

    assertThatThrownBy(() -> migrateToV18("g2case2"))
        .hasStackTraceContaining("V18 preflight")
        .hasStackTraceContaining("counterparty " + dirty + " normalizes to")
        .hasStackTraceContaining("already held by counterparty " + clean);
  }

  /**
   * The holder in case 2 is deliberately not filtered by {@code merged_into}, because a tombstone
   * occupies the {@code uq_counterparty_identity} slot just as a live row does. When it is one,
   * the diagnostic has to say so: {@code merge_counterparty} rejects a folded id as its own
   * target, so the operator's remedy is the tombstone's merge target, not the tombstone.
   */
  @Test
  void preflightCase2NamesTheMergeTargetWhenTheHolderIsATombstone() {
    DSLContext db = migrate("g2case2tomb", "17");
    long target = insertCounterparty(db, "name", "SYNTH KAPPA HOST", "Synth Kappa Host");
    long tombstone = insertTombstone(db, "SYNTH LAMBDA", "Synth Lambda", target);
    long dirty = insertCounterparty(db, "name", "SYNTH  LAMBDA", "Synth Lambda");

    assertThatThrownBy(() -> migrateToV18("g2case2tomb"))
        .hasStackTraceContaining("counterparty " + dirty + " normalizes to")
        .hasStackTraceContaining(
            "already held by counterparty "
                + tombstone
                + " (a merge tombstone folded into "
                + target
                + ")");
  }

  @Test
  void preflightCase3RejectsTwoDirtyAliasesCollidingWithEachOther() {
    DSLContext db = migrate("g2case3", "17");
    // Both aliases hang off one arbitrary host counterparty purely to satisfy the FK; the guard
    // is about the alias pair, so the host's id never appears in the diagnostic.
    long host = insertCounterparty(db, "name", "SYNTH GAMMA HOST", "Synth Gamma Host");
    long a = insertAlias(db, "SYNTH  DELTA", host);
    long b = insertAlias(db, "SYNTH   DELTA", host);

    assertThatThrownBy(() -> migrateToV18("g2case3"))
        .hasStackTraceContaining("V18 preflight")
        .hasStackTraceContaining("aliases " + a + " and " + b + " both normalize to");
  }

  @Test
  void preflightCase4RejectsDirtyAliasDivertingALivingCounterpartysIdentity() {
    DSLContext db = migrate("g2case4", "17");
    long owner = insertCounterparty(db, "name", "SYNTH EPSILON", "Synth Epsilon");
    long other = insertCounterparty(db, "name", "SYNTH ZETA HOST", "Synth Zeta Host");
    long alias = insertAlias(db, "SYNTH  EPSILON", other);

    assertThatThrownBy(() -> migrateToV18("g2case4"))
        .hasStackTraceContaining("V18 preflight")
        .hasStackTraceContaining("alias " + alias + " (canonical " + other + ") normalizes to")
        .hasStackTraceContaining("the identity of live counterparty " + owner)
        .hasStackTraceContaining("would be diverted away from its own row");
  }

  /**
   * The mirror image of case 4: the dirty row is the counterparty and the collision partner is a
   * clean alias. No unique constraint would fire (different tables), but normalizing the
   * counterparty hands its identity to the alias's canonical target, because resolution reads
   * {@code COALESCE(alias.canonical_counterparty_id, own.id)} and the alias wins.
   */
  @Test
  void preflightCase6RejectsDirtyCounterpartyLandingOnAnAliasPointingElsewhere() {
    DSLContext db = migrate("g2case6", "17");
    long dirty = insertCounterparty(db, "name", "SYNTH  ZETA", "Synth Zeta");
    long other = insertCounterparty(db, "name", "SYNTH THETA HOST", "Synth Theta Host");
    long alias = insertAlias(db, "SYNTH ZETA", other);

    assertThatThrownBy(() -> migrateToV18("g2case6"))
        .hasStackTraceContaining("V18 preflight")
        .hasStackTraceContaining("counterparty " + dirty + " normalizes to")
        .hasStackTraceContaining("already held by alias " + alias + " (canonical " + other + ")")
        .hasStackTraceContaining("diverted away from the counterparty's own row");
  }

  /**
   * The exclusion that keeps case 6 from being over-broad, and the counterpart that makes it
   * trustworthy: the same shape, but the alias already points at the dirty counterparty itself.
   * {@code COALESCE(alias.canonical, own.id)} yields that same id before and after the backfill,
   * so nothing is diverted and the migration must go through.
   */
  @Test
  void preflightCase6AllowsAnAliasPointingAtTheDirtyCounterpartyItself() {
    DSLContext db = migrate("g2case6ok", "17");
    long dirty = insertCounterparty(db, "name", "SYNTH  IOTA", "Synth Iota");
    long alias = insertAlias(db, "SYNTH IOTA", dirty);

    migrateToV18("g2case6ok");

    assertThat(
            db.fetchOne("select identity_value from counterparties where id = ?", dirty)
                .get(0, String.class))
        .isEqualTo("SYNTH IOTA");
    assertThat(
            db.fetchOne(
                    "select canonical_counterparty_id from counterparty_alias where id = ?", alias)
                .get(0, Long.class))
        .isEqualTo(dirty);
  }

  /**
   * The convergence case: counterparty and alias are BOTH dirty, so neither case 4 nor case 6
   * matches (each compares one side's normalized value against the other's current one) and the
   * two only collide once the backfill has run. Diverts resolution exactly as cases 4 and 6 would,
   * with no unique violation to catch it.
   */
  @Test
  void preflightCase7RejectsADirtyCounterpartyAndDirtyAliasConvergingOnEachOther() {
    DSLContext db = migrate("g2case7", "17");
    long dirty = insertCounterparty(db, "name", "SYNTH  MU", "Synth Mu");
    long other = insertCounterparty(db, "name", "SYNTH NU HOST", "Synth Nu Host");
    long alias = insertAlias(db, "SYNTH   MU", other);

    assertThatThrownBy(() -> migrateToV18("g2case7"))
        .hasStackTraceContaining("V18 preflight")
        .hasStackTraceContaining(
            "counterparty " + dirty + " and alias " + alias + " (canonical " + other + ")")
        .hasStackTraceContaining("both normalize to 'SYNTH MU'")
        .hasStackTraceContaining("diverted away from the counterparty's own row");
  }

  /**
   * The exclusion that keeps case 7 honest: the same double-dirty convergence, but the alias
   * already points at that very counterparty, so {@code COALESCE(alias.canonical, own.id)} yields
   * the same id before and after and nothing is diverted.
   */
  @Test
  void preflightCase7AllowsConvergenceOntoTheAliasesOwnCounterparty() {
    DSLContext db = migrate("g2case7ok", "17");
    long dirty = insertCounterparty(db, "name", "SYNTH  XI", "Synth Xi");
    long alias = insertAlias(db, "SYNTH   XI", dirty);

    migrateToV18("g2case7ok");

    assertThat(
            db.fetchOne("select identity_value from counterparties where id = ?", dirty)
                .get(0, String.class))
        .isEqualTo("SYNTH XI");
    assertThat(
            db.fetchOne("select identity_value from counterparty_alias where id = ?", alias)
                .get(0, String.class))
        .isEqualTo("SYNTH XI");
  }

  /**
   * The exemption. Seeded the way {@code merge_counterparty} actually produces it: the folded
   * source survives as a tombstone AND {@code CounterpartyMergeService} copies its identity_value
   * verbatim into an alias row pointing at the target. A tombstone without its alias would not
   * reproduce the hazard, so this test would be vacuous without both rows.
   */
  @Test
  void preflightCase5AllowsADirtyTombstoneAndItsOwnAlias() {
    DSLContext db = migrate("g2case5", "17");
    long target = insertCounterparty(db, "name", "SYNTH ETA", "Synth Eta");
    long tombstone = insertTombstone(db, "SYNTH  ETA", "Synth Eta", target);
    long alias = insertAlias(db, "SYNTH  ETA", target);

    migrateToV18("g2case5");

    // The backfill skipped the tombstone: its identity is inert, but it must keep its slot in
    // uq_counterparty_identity byte-identically to what the export produces.
    assertThat(
            db.fetchOne("select identity_value from counterparties where id = ?", tombstone)
                .get(0, String.class))
        .isEqualTo("SYNTH  ETA");

    // The alias was normalized -- and lands on the target it already pointed at, so
    // COALESCE(alias.canonical, own.id) is unchanged.
    assertThat(
            db.fetchOne("select identity_value from counterparty_alias where id = ?", alias)
                .get(0, String.class))
        .isEqualTo("SYNTH ETA");
    assertThat(
            db.fetchOne(
                    "select canonical_counterparty_id from counterparty_alias where id = ?", alias)
                .get(0, Long.class))
        .isEqualTo(target);
  }
}
