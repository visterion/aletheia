package de.visterion.aletheia.tagrules;

import static de.visterion.aletheia.jooq.Tables.TAG_RULES;

import de.visterion.aletheia.substrate.SubstrateLock;
import de.visterion.aletheia.substrate.TransactionLayerSql;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies persistent auto-tagging rules (#37, spec §4/§5). Deterministic: no LLM. Runs after the
 * counterparty/contract resolvers on startup ({@code @Order(5)}), and is called by the HTTP ingest,
 * {@code reattribute_transaction}, and {@code create_tag_rule} backfill.
 *
 * <p>Each rule is applied in its own {@link TransactionTemplate} unit (NOT {@code @Transactional} --
 * this bean self-invokes, which would bypass the proxy and open no transaction) inside
 * {@link SubstrateLock}. Per matched counterparty, per target dimension: skip if a {@code confirmed}
 * tag exists there; else delete the dimension's {@code auto} tags and insert the rule's value(s) as
 * {@code confirmed}, appending a {@code counterparty_history} row.
 */
@Component
@Order(5)
public class TagRuleResolver implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TagRuleResolver.class);

  /** Whitelisted raw column per matchable field (never user text -> injection-safe). */
  private static String column(RuleField field) {
    return switch (field) {
      case remittance_info -> "remittance_info";
      case counterparty_name -> "counterparty_name";
      case creditor_id -> "creditor_id";
      case counterparty_iban -> "counterparty_iban";
      case direction -> "direction";
    };
  }

  // Raw-root transactions with the resolver's identity CASE (verbatim from CounterpartyResolver)
  // plus the matchable raw columns, joined to counterparties. Conditions are appended to WHERE.
  private static final String MATCH_BASE =
      """
      SELECT DISTINCT cp.id AS id
      FROM (
        SELECT
            CASE
                WHEN attributed_name IS NOT NULL THEN 'name'
                WHEN creditor_id IS NOT NULL THEN 'creditor_id'
                WHEN counterparty_iban IS NOT NULL THEN 'iban'
                WHEN normalized_name <> '' THEN 'name'
            END AS identity_type,
            CASE
                WHEN attributed_name IS NOT NULL THEN upper(normalized_attributed)
                WHEN creditor_id IS NOT NULL THEN creditor_id
                WHEN counterparty_iban IS NOT NULL THEN counterparty_iban
                WHEN normalized_name <> '' THEN upper(normalized_name)
            END AS identity_value,
            remittance_info, counterparty_name, creditor_id, counterparty_iban, direction
        FROM (
            SELECT
                creditor_id, counterparty_iban, counterparty_name, attributed_name,
                remittance_info, direction,
                trim(regexp_replace(normalize(counterparty_name, NFC), '\\s+', ' ', 'g')) AS normalized_name,
                trim(regexp_replace(normalize(attributed_name, NFC), '\\s+', ' ', 'g')) AS normalized_attributed
            FROM transactions
            WHERE \s"""
          + TransactionLayerSql.RAW_ROOT_PREDICATE
          + """
        ) normalized
        WHERE (CASE
                WHEN attributed_name IS NOT NULL THEN 'name'
                WHEN creditor_id IS NOT NULL THEN 'creditor_id'
                WHEN counterparty_iban IS NOT NULL THEN 'iban'
                WHEN normalized_name <> '' THEN 'name'
            END) IS NOT NULL
      ) t
      JOIN counterparties cp
        ON cp.identity_type = t.identity_type AND cp.identity_value = t.identity_value
      WHERE cp.status <> 'dismissed'
      """;

  private final DSLContext db;
  private final TransactionTemplate tx;
  private final SubstrateLock substrateLock;
  private final ObjectMapper mapper;

  public TagRuleResolver(
      DSLContext db,
      PlatformTransactionManager txManager,
      SubstrateLock substrateLock,
      ObjectMapper mapper) {
    this.db = db;
    this.tx = new TransactionTemplate(txManager);
    this.substrateLock = substrateLock;
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      resolve();
    } catch (RuntimeException e) {
      log.warn(
          "Startup tag-rule application failed; will retry on next ingest/restart: {}",
          e.toString());
    }
  }

  /**
   * Applies every enabled rule; one bad rule (unparseable JSON or a failing apply) is logged and
   * skipped, the rest still apply. Rows are fetched once, but JSON parsing happens per rule inside
   * the loop so one unparseable row cannot abort the whole batch before any other rule gets a
   * chance to run.
   */
  public void resolve() {
    substrateLock.lock();
    try {
      for (Record r : db.fetch(LOAD_ENABLED_RULES_SQL)) {
        long id = r.get("id", Long.class);
        String name = r.get("name", String.class);
        try {
          StoredRule rule = toStoredRule(r);
          tx.executeWithoutResult(status -> applyInTx(rule));
        } catch (RuntimeException e) {
          log.warn("Skipping tag rule {} ('{}'): {}", id, name, e.toString());
        }
      }
    } finally {
      substrateLock.unlock();
    }
  }

  /** Applies one rule (backfill entry point); returns the number of counterparties changed. */
  public int applyRule(StoredRule rule) {
    substrateLock.lock();
    try {
      return tx.execute(status -> applyInTx(rule));
    } finally {
      substrateLock.unlock();
    }
  }

  /** Result of {@link #createRule}: the new rule's id and how many counterparties it changed. */
  public record RuleCreateResult(long ruleId, int appliedCount) {}

  /**
   * Persists a new (validated) rule row and, if {@code backfill}, applies it -- both inside ONE
   * {@link TransactionTemplate} unit under {@link SubstrateLock} (spec §4.2 atomicity): a backfill
   * failure rolls back the rule row too, so an enabled rule is never left half-applied.
   */
  public RuleCreateResult createRule(
      String name, List<RuleCondition> conditions, List<RuleAction> actions, boolean backfill) {
    substrateLock.lock();
    try {
      return tx.execute(
          status -> {
            long id = insertRuleRow(name, conditions, actions);
            int applied = backfill ? applyToCounterparties(matchedCounterpartyIds(conditions), actions) : 0;
            return new RuleCreateResult(id, applied);
          });
    } finally {
      substrateLock.unlock();
    }
  }

  /** Persists a validated rule row and returns its generated id. */
  private long insertRuleRow(String name, List<RuleCondition> conditions, List<RuleAction> actions) {
    JSONB conditionsJson = JSONB.valueOf(mapper.writeValueAsString(conditions));
    JSONB actionsJson = JSONB.valueOf(mapper.writeValueAsString(actions));
    return db.insertInto(TAG_RULES)
        .set(TAG_RULES.NAME, name)
        .set(TAG_RULES.CONDITIONS, conditionsJson)
        .set(TAG_RULES.ACTIONS, actionsJson)
        .returning(TAG_RULES.ID)
        .fetchOne()
        .getId();
  }

  private int applyInTx(StoredRule rule) {
    List<Long> ids = matchedCounterpartyIds(rule.conditions());
    return applyToCounterparties(ids, rule.actions());
  }

  /** The match set for a rule's conditions (also used by dry-run + the blast-radius gate). */
  public List<Long> matchedCounterpartyIds(List<RuleCondition> conditions) {
    StringBuilder sql = new StringBuilder(MATCH_BASE);
    List<Object> binds = new ArrayList<>();
    for (RuleCondition c : conditions) {
      String col = column(c.field());
      if (c.op() == RuleOp.contains) {
        sql.append(" AND t.").append(col).append(" ILIKE ? ESCAPE '\\'");
        binds.add("%" + escapeLike(c.value()) + "%");
      } else {
        sql.append(" AND t.").append(col).append(" = ?");
        binds.add(c.value());
      }
    }
    return db.fetch(sql.toString(), binds.toArray()).map(r -> r.get("id", Long.class));
  }

  /** Dry-run: how many matched counterparties would actually change (not fully confirmed already). */
  public int countWouldChange(List<Long> matchedIds, List<RuleAction> actions) {
    int count = 0;
    List<String> dims = actions.stream().map(RuleAction::dimension).distinct().toList();
    for (long id : matchedIds) {
      for (String dim : dims) {
        if (!hasConfirmed(id, dim)) {
          count++;
          break;
        }
      }
    }
    return count;
  }

  private int applyToCounterparties(List<Long> ids, List<RuleAction> actions) {
    List<String> dims = actions.stream().map(RuleAction::dimension).distinct().toList();
    int changed = 0;
    for (long id : ids) {
      boolean touched = false;
      for (String dim : dims) {
        if (hasConfirmed(id, dim)) {
          continue; // a confirmed decision stands; skip this dimension entirely
        }
        List<String> oldValues =
            db.fetch(
                    "SELECT value FROM counterparty_tags WHERE counterparty_id=? AND dimension=? ORDER BY value",
                    id, dim)
                .map(r -> r.get("value", String.class));
        db.execute(
            "DELETE FROM counterparty_tags WHERE counterparty_id=? AND dimension=? AND source='auto'",
            id, dim);
        List<String> newValues =
            actions.stream()
                .filter(a -> a.dimension().equals(dim))
                .map(RuleAction::value)
                .distinct()
                .toList();
        for (String value : newValues) {
          db.execute(
              "INSERT INTO counterparty_tags (counterparty_id, dimension, value, source) "
                  + "VALUES (?, ?, ?, 'confirmed') ON CONFLICT DO NOTHING",
              id, dim, value);
        }
        db.execute(
            "INSERT INTO counterparty_history (counterparty_id, field, old_value, new_value, source, actor) "
                + "VALUES (?, ?, ?, ?, 'confirmed', 'tag-rule')",
            id, "tag:" + dim, String.join(",", oldValues), String.join(",", newValues));
        touched = true;
      }
      if (touched) {
        changed++;
      }
    }
    return changed;
  }

  private boolean hasConfirmed(long counterpartyId, String dimension) {
    return (Boolean)
        db.fetchValue(
            "SELECT EXISTS (SELECT 1 FROM counterparty_tags "
                + "WHERE counterparty_id=? AND dimension=? AND source='confirmed')",
            counterpartyId, dimension);
  }

  private static final String LOAD_ENABLED_RULES_SQL =
      "SELECT id, name, enabled, conditions, actions FROM tag_rules WHERE enabled ORDER BY id";

  /** Loads every rule (enabled or not) for {@code list_tag_rules}. */
  public List<StoredRule> loadEnabledRulesIncludingDisabled() {
    return db.fetch("SELECT id, name, enabled, conditions, actions FROM tag_rules ORDER BY id")
        .map(this::toStoredRule);
  }

  public StoredRule loadRule(long id) {
    Record r =
        db.fetchOne("SELECT id, name, enabled, conditions, actions FROM tag_rules WHERE id=?", id);
    if (r == null) {
      throw new IllegalArgumentException("no such tag rule: " + id);
    }
    return toStoredRule(r);
  }

  private StoredRule toStoredRule(Record r) {
    try {
      List<RuleCondition> conditions =
          mapper.readValue(
              ((JSONB) r.get("conditions")).data(), new TypeReference<List<RuleCondition>>() {});
      List<RuleAction> actions =
          mapper.readValue(
              ((JSONB) r.get("actions")).data(), new TypeReference<List<RuleAction>>() {});
      return new StoredRule(
          r.get("id", Long.class),
          r.get("name", String.class),
          r.get("enabled", Boolean.class),
          conditions,
          actions);
    } catch (Exception e) {
      throw new IllegalArgumentException("rule " + r.get("id") + " has unparseable JSON: " + e.getMessage());
    }
  }

  private static String escapeLike(String v) {
    return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
