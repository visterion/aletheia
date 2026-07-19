package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.mcp.Cashflow.CashflowExcluded;
import de.visterion.aletheia.mcp.Cashflow.CashflowLink;
import de.visterion.aletheia.mcp.Cashflow.CashflowNode;
import de.visterion.aletheia.mcp.Cashflow.CashflowParams;
import de.visterion.aletheia.mcp.Cashflow.CashflowRole;
import de.visterion.aletheia.mcp.Cashflow.CashflowRow;
import de.visterion.aletheia.mcp.Cashflow.InvestmentMode;
import de.visterion.aletheia.mcp.Cashflow.RoleKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CashflowGraphBuilderTest {

  private final CashflowGraphBuilder builder = new CashflowGraphBuilder();

  private static final Map<RoleKey, CashflowRole> ROLE_MAP =
      Map.of(
          new RoleKey("domain", "einkommen"), CashflowRole.INCOME,
          new RoleKey("domain", "transfer-privat"), CashflowRole.TRANSFER,
          new RoleKey("nature", "umbuchung"), CashflowRole.TRANSFER,
          new RoleKey("nature", "investment"), CashflowRole.DEPOT,
          new RoleKey("nature", "zahlungsdienst"), CashflowRole.PASSTHROUGH);

  private static CashflowParams params(
      boolean excludeTransfers, boolean excludePassthroughs, InvestmentMode mode) {
    return new CashflowParams(
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 30),
        List.of("income_source", "domain", "counterparty"),
        excludeTransfers,
        excludePassthroughs,
        mode,
        6,
        BigDecimal.ZERO);
  }

  private static CashflowParams defaults() {
    return params(true, true, InvestmentMode.AS_SAVING);
  }

  private static CashflowRow row(
      Long cp, String label, String domain, String nature, String dir, String amount) {
    return new CashflowRow(
        cp, label, domain, nature, dir, new BigDecimal(amount), null, null, "ID-" + cp);
  }

  private static BigDecimal nodeValue(Cashflow cf, String id) {
    return cf.nodes().stream()
        .filter(n -> n.id().equals(id))
        .map(CashflowNode::value)
        .findFirst()
        .orElse(null);
  }

  private static BigDecimal linkValue(Cashflow cf, String source, String target) {
    return cf.links().stream()
        .filter(l -> l.source().equals(source) && l.target().equals(target))
        .map(CashflowLink::value)
        .findFirst()
        .orElse(null);
  }

  /** Every interior node balances: Σ inbound == Σ outbound. Terminal nodes are exempt. */
  private static void assertInteriorNodesBalanced(Cashflow cf) {
    Set<String> terminalKinds = Set.of("income", "balance");
    for (CashflowNode n : cf.nodes()) {
      boolean isLeafExpenseOrSaving =
          (n.kind().equals("expense") || n.kind().equals("saving"))
              && cf.links().stream().noneMatch(l -> l.source().equals(n.id()));
      if (terminalKinds.contains(n.kind()) || isLeafExpenseOrSaving || n.id().equals("budget:surplus")
          || n.id().equals("transfer:internal")) {
        continue; // terminal by design
      }
      BigDecimal in =
          cf.links().stream()
              .filter(l -> l.target().equals(n.id()))
              .map(CashflowLink::value)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal out =
          cf.links().stream()
              .filter(l -> l.source().equals(n.id()))
              .map(CashflowLink::value)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      assertThat(in).as("interior node %s balanced", n.id()).isEqualByComparingTo(out);
    }
    assertThat(cf.links()).allSatisfy(l -> assertThat(l.value()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
  }

  @Test
  void simpleIncomeAndExpenseBalances() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "3000.00"),
            row(2L, "Rewe", "lebensmittel", null, "DBIT", "400.00"),
            row(3L, "Eprimo", "energie", null, "DBIT", "100.00"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(cf.meta().income()).isEqualByComparingTo("3000.00");
    assertThat(cf.meta().outflow()).isEqualByComparingTo("500.00");
    assertThat(cf.meta().saving()).isEqualByComparingTo("0.00");
    assertThat(cf.meta().saldo()).isEqualByComparingTo("2500.00");
    // surplus (income > outflow) routed to budget:surplus
    assertThat(nodeValue(cf, "budget:surplus")).isEqualByComparingTo("2500.00");
    assertThat(nodeValue(cf, "domain:lebensmittel")).isEqualByComparingTo("400.00");
    assertThat(linkValue(cf, "income:1", "budget:main")).isEqualByComparingTo("3000.00");
    assertThat(linkValue(cf, "domain:lebensmittel", "cp:2")).isEqualByComparingTo("400.00");
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void outflowExceedingIncomeAddsBalanceNode() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(2L, "Rewe", "lebensmittel", null, "DBIT", "1500.00"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(cf.meta().saldo()).isEqualByComparingTo("-500.00");
    assertThat(nodeValue(cf, "balance:reserves")).isEqualByComparingTo("500.00");
    assertThat(linkValue(cf, "balance:reserves", "budget:main")).isEqualByComparingTo("500.00");
    assertThat(nodeValue(cf, "budget:surplus")).isNull();
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void expenseRefundFlooredAndReportedInRefundsIn() {
    // Rewe: 400 spend, 500 refund in the same period -> net -100 money-in; node floored to 0.
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(2L, "Rewe", "lebensmittel", null, "DBIT", "400.00"),
            row(2L, "Rewe", "lebensmittel", null, "CRDT", "500.00"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(nodeValue(cf, "cp:2")).isNull(); // floored to 0 -> no node
    assertThat(cf.meta().excluded().refundsIn()).isEqualByComparingTo("100.00");
    assertThat(cf.meta().outflow()).isEqualByComparingTo("0.00");
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void incomeClawbackFlooredAndReportedInClawbacksOut() {
    // Employer: 1000 pay, 1200 clawback -> net -200 money-out; income node floored to 0.
    var rows = List.of(row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
        row(1L, "Employer", "einkommen", null, "DBIT", "1200.00"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(nodeValue(cf, "income:1")).isNull();
    assertThat(cf.meta().excluded().clawbacksOut()).isEqualByComparingTo("200.00");
    assertThat(cf.meta().income()).isEqualByComparingTo("0.00");
  }

  @Test
  void internalTransferNettedIntoMetaAndNotShownByDefault() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(9L, "Own Savings", "transfer-privat", null, "DBIT", "300.00"),
            row(9L, "Own Savings", "transfer-privat", null, "CRDT", "50.00"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    // internal_net = 50 - 300 = -250
    assertThat(cf.meta().excluded().internal()).isEqualByComparingTo("-250.00");
    assertThat(nodeValue(cf, "transfer:internal")).isNull(); // excluded by default -> no node
    // not counted as income or expense
    assertThat(cf.meta().income()).isEqualByComparingTo("1000.00");
    assertThat(cf.meta().outflow()).isEqualByComparingTo("0.00");
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void internalTransferRenderedBalancesBudgetWhenNotExcluded() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(9L, "Own Savings", "transfer-privat", null, "DBIT", "300.00"));
    // internal_net = -300 -> budget:main -> transfer:internal of 300
    var cf = builder.build(rows, ROLE_MAP, Set.of(), params(false, true, InvestmentMode.AS_SAVING));

    assertThat(nodeValue(cf, "transfer:internal")).isEqualByComparingTo("300.00");
    assertThat(linkValue(cf, "budget:main", "transfer:internal")).isEqualByComparingTo("300.00");
    assertThat(cf.meta().excluded().internal()).isEqualByComparingTo("-300.00");
    assertInteriorNodesBalanced(cf); // budget:main must still balance
  }

  @Test
  void depotDividendExcludedFromIncomeAndBuyBecomesSaving() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(5L, "Broker", null, "investment", "CRDT", "40.00"),   // dividend
            row(5L, "Broker", null, "investment", "DBIT", "200.00")); // ETF buy
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(cf.meta().excluded().depotIncome()).isEqualByComparingTo("40.00");
    assertThat(cf.meta().income()).isEqualByComparingTo("1000.00"); // dividend NOT income
    assertThat(nodeValue(cf, "depot:buys")).isEqualByComparingTo("200.00");
    assertThat(cf.meta().saving()).isEqualByComparingTo("200.00");
    assertThat(cf.meta().excluded().depotBuys()).isEqualByComparingTo("0.00");
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void depotBuyExcludedModeReportedNotRendered() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(5L, "Broker", null, "investment", "DBIT", "200.00"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), params(true, true, InvestmentMode.EXCLUDE));

    assertThat(nodeValue(cf, "depot:buys")).isNull();
    assertThat(cf.meta().excluded().depotBuys()).isEqualByComparingTo("200.00");
    assertThat(cf.meta().saving()).isEqualByComparingTo("0.00");
  }

  @Test
  void opaquePassthroughRemovedByDefaultAndAttributedFlowsToMerchant() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            // opaque: nature=zahlungsdienst, attribution_source null
            new CashflowRow(7L, "Adyen", null, "zahlungsdienst", "DBIT", new BigDecimal("80.00"),
                "shop", null, "ID-7"),
            // attributed passthrough: effective_cp is the real merchant, its domain is lebensmittel
            new CashflowRow(8L, "Real Shop", "lebensmittel", "zahlungsdienst", "DBIT",
                new BigDecimal("30.00"), "shop", "manual", "ID-8"));
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(cf.meta().excluded().passthrough()).isEqualByComparingTo("-80.00"); // 0 crdt - 80 dbit
    assertThat(nodeValue(cf, "cp:7")).isNull();
    // attributed one is a normal expense on the merchant
    assertThat(nodeValue(cf, "cp:8")).isEqualByComparingTo("30.00");
    assertThat(nodeValue(cf, "domain:lebensmittel")).isEqualByComparingTo("30.00");
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void payerPinnedOverrideMakesCounterpartyIncomeBothDirections() {
    // Landesoberkasse tagged krankenversicherung (an expense domain) but configured as income payer.
    var rows =
        List.of(
            new CashflowRow(4L, "Landesoberkasse", "krankenversicherung", null, "CRDT",
                new BigDecimal("500.00"), "Beihilfe", null, "LOK-1"));
    var cf = builder.build(rows, ROLE_MAP, Set.of("LOK-1"), defaults());

    assertThat(nodeValue(cf, "income:4")).isEqualByComparingTo("500.00");
    assertThat(cf.meta().income()).isEqualByComparingTo("500.00");
    assertThat(nodeValue(cf, "domain:krankenversicherung")).isNull(); // not an expense
  }

  @Test
  void untaggedExpenseAndNullCounterpartyFallIntoUntaggedSonstiges() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(2L, "NoTag Co", null, null, "DBIT", "40.00"),       // resolved, untagged
            new CashflowRow(null, null, null, null, "DBIT", new BigDecimal("10.00"), null, null, null)); // unresolved
    var cf = builder.build(rows, ROLE_MAP, Set.of(), defaults());

    assertThat(nodeValue(cf, "domain:(untagged)")).isEqualByComparingTo("50.00");
    assertThat(cf.meta().outflow()).isEqualByComparingTo("50.00");
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void topNBundlesRemainderIntoSonstigesDeterministically() {
    var rows = new java.util.ArrayList<CashflowRow>();
    rows.add(row(1L, "Employer", "einkommen", null, "CRDT", "10000.00"));
    // 8 lebensmittel counterparties with descending amounts 80,70,...,10
    for (int i = 0; i < 8; i++) {
      rows.add(row(100L + i, "Shop" + i, "lebensmittel", null, "DBIT",
          String.valueOf((8 - i) * 10) + ".00"));
    }
    var p = new CashflowParams(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
        List.of("income_source", "domain", "counterparty"), true, true, InvestmentMode.AS_SAVING, 6,
        BigDecimal.ZERO);
    var cf = builder.build(rows, ROLE_MAP, Set.of(), p);

    // top 6 cps rendered individually, remaining 2 (amounts 20,10 -> 30) bundled
    assertThat(nodeValue(cf, "sonstiges:lebensmittel")).isEqualByComparingTo("30.00");
    // domain total unchanged
    assertThat(nodeValue(cf, "domain:lebensmittel")).isEqualByComparingTo("360.00"); // 80+70+..+10
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void emptyInputYieldsEmptyGraphAndZeroMeta() {
    var cf = builder.build(List.of(), ROLE_MAP, Set.of(), defaults());
    assertThat(cf.nodes()).isEmpty();
    assertThat(cf.links()).isEmpty();
    assertThat(cf.meta().income()).isEqualByComparingTo("0.00");
    assertThat(cf.meta().outflow()).isEqualByComparingTo("0.00");
    assertThat(cf.meta().saldo()).isEqualByComparingTo("0.00");
    assertThat(cf.meta().excluded().internal()).isEqualByComparingTo("0.00");
  }

  @Test
  void deterministicAcrossTwoBuilds() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(2L, "Rewe", "lebensmittel", null, "DBIT", "100.00"),
            row(3L, "Edeka", "lebensmittel", null, "DBIT", "100.00")); // tie -> id ASC
    var a = builder.build(rows, ROLE_MAP, Set.of(), defaults());
    var b = builder.build(rows, ROLE_MAP, Set.of(), defaults());
    assertThat(a).isEqualTo(b);
    // tie broken by id ASC: cp:2 before cp:3 in the links list order under domain:lebensmittel
    List<String> domainChildOrder =
        a.links().stream()
            .filter(l -> l.source().equals("domain:lebensmittel"))
            .map(CashflowLink::target)
            .toList();
    assertThat(domainChildOrder).containsExactly("cp:2", "cp:3");
  }

  @Test
  void savingFlooredPerCounterpartyNotAcrossValue() {
    Map<RoleKey, CashflowRole> roleMap =
        Map.of(
            new RoleKey("domain", "einkommen"), CashflowRole.INCOME,
            new RoleKey("domain", "ruecklage"), CashflowRole.SAVING);
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(20L, "Saver A", "ruecklage", null, "DBIT", "100.00"), // nets +100
            row(21L, "Saver B", "ruecklage", null, "CRDT", "30.00")); // nets -30, floors to 0
    var cf = builder.build(rows, roleMap, Set.of(), defaults());

    // per-CP flooring: A contributes 100, B floors to 0 (its 30 -> refundsIn).
    // value-level netting would incorrectly give 100 - 30 = 70.
    assertThat(nodeValue(cf, "saving:ruecklage")).isEqualByComparingTo("100.00");
    assertThat(cf.meta().excluded().refundsIn()).isGreaterThanOrEqualTo(new BigDecimal("30.00"));
    assertInteriorNodesBalanced(cf);
  }

  @Test
  void levelsOmittingCounterpartyStopsAtDomain() {
    var rows =
        List.of(
            row(1L, "Employer", "einkommen", null, "CRDT", "1000.00"),
            row(2L, "Rewe", "lebensmittel", null, "DBIT", "100.00"),
            row(3L, "Edeka", "lebensmittel", null, "DBIT", "50.00"));

    var fullParams =
        new CashflowParams(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            List.of("income_source", "domain", "counterparty"),
            true,
            true,
            InvestmentMode.AS_SAVING,
            6,
            BigDecimal.ZERO);
    var shortParams =
        new CashflowParams(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            List.of("income_source", "domain"),
            true,
            true,
            InvestmentMode.AS_SAVING,
            6,
            BigDecimal.ZERO);

    var full = builder.build(rows, ROLE_MAP, Set.of(), fullParams);
    var shortCf = builder.build(rows, ROLE_MAP, Set.of(), shortParams);

    assertThat(nodeValue(full, "domain:lebensmittel")).isEqualByComparingTo("150.00");
    assertThat(nodeValue(shortCf, "domain:lebensmittel")).isEqualByComparingTo("150.00");

    assertThat(shortCf.nodes()).noneMatch(n -> n.id().startsWith("cp:"));
    assertThat(shortCf.nodes()).noneMatch(n -> n.id().startsWith("sonstiges:"));
    assertThat(shortCf.links()).noneMatch(l -> l.source().equals("domain:lebensmittel"));
  }
}
