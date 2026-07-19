package de.visterion.aletheia.mcp;

import de.visterion.aletheia.mcp.Cashflow.CashflowExcluded;
import de.visterion.aletheia.mcp.Cashflow.CashflowLink;
import de.visterion.aletheia.mcp.Cashflow.CashflowMeta;
import de.visterion.aletheia.mcp.Cashflow.CashflowNode;
import de.visterion.aletheia.mcp.Cashflow.CashflowParams;
import de.visterion.aletheia.mcp.Cashflow.CashflowRole;
import de.visterion.aletheia.mcp.Cashflow.CashflowRow;
import de.visterion.aletheia.mcp.Cashflow.InvestmentMode;
import de.visterion.aletheia.mcp.Cashflow.RoleKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure, side-effect-free builder that turns already-fetched, identity- and tag-resolved {@link
 * CashflowRow}s into a balanced, Sankey-ready {@link Cashflow} ({@code nodes}, {@code links}, {@code
 * meta}). No Spring/DB dependencies at runtime — annotated {@code @Component} only so the service
 * layer can autowire it. See the "Roles &amp; precedence" section of the cashflow Stage-2 plan.
 */
@Component
public class CashflowGraphBuilder {

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  /** Mutable accumulator of the credited/debited totals for one aggregation key. */
  private static final class Flow {
    BigDecimal crdt = ZERO;
    BigDecimal dbit = ZERO;
    String label;

    void add(String direction, BigDecimal amount, String rowLabel) {
      if ("CRDT".equals(direction)) {
        crdt = crdt.add(amount);
      } else {
        dbit = dbit.add(amount);
      }
      if (label == null && rowLabel != null) {
        label = rowLabel;
      }
    }

    /** Money-in minus money-out. */
    BigDecimal net() {
      return crdt.subtract(dbit);
    }
  }

  /** A rendered leaf below a parent node. */
  private record Leaf(String id, String label, BigDecimal value) {}

  public Cashflow build(
      List<CashflowRow> rows,
      Map<RoleKey, CashflowRole> roleMap,
      Set<String> incomePayerIds,
      CashflowParams params) {

    // --- Aggregation buckets -------------------------------------------------
    Map<String, Flow> incomeById = new LinkedHashMap<>();
    Map<String, Map<String, Flow>> expenseByDomain = new LinkedHashMap<>();
    Map<String, Flow> savingByValue = new LinkedHashMap<>();
    Flow depot = new Flow();
    Flow transfer = new Flow();
    Flow passthroughOpaque = new Flow();

    boolean renderPassthrough = !params.excludePassthroughs();

    for (CashflowRow r : rows) {
      CashflowRole role = roleOf(r, roleMap, incomePayerIds);
      switch (role) {
        case INCOME ->
            incomeById.computeIfAbsent(incomeId(r), k -> new Flow())
                .add(r.direction(), r.amount(), r.label());
        case EXPENSE ->
            putExpense(expenseByDomain, domainValue(r.domainTag()), r);
        case SAVING ->
            savingByValue.computeIfAbsent(savingValue(r, roleMap), k -> new Flow())
                .add(r.direction(), r.amount(), r.label());
        case DEPOT -> depot.add(r.direction(), r.amount(), r.label());
        case TRANSFER -> transfer.add(r.direction(), r.amount(), r.label());
        case PASSTHROUGH -> {
          if (renderPassthrough) {
            putExpense(expenseByDomain, "zahlungsdienst", r);
          } else {
            passthroughOpaque.add(r.direction(), r.amount(), r.label());
          }
        }
      }
    }

    // --- Netting into rendered leaves + floored remainders -------------------
    BigDecimal refundsIn = ZERO;
    BigDecimal clawbacksOut = ZERO;

    // Income sources.
    List<Leaf> incomeLeaves = new ArrayList<>();
    for (Map.Entry<String, Flow> e : incomeById.entrySet()) {
      BigDecimal natural = e.getValue().net(); // money-in
      if (natural.signum() > 0) {
        incomeLeaves.add(new Leaf(e.getKey(), labelOr(e.getValue().label, e.getKey()), natural));
      } else if (natural.signum() < 0) {
        clawbacksOut = clawbacksOut.add(natural.negate());
      }
    }
    BigDecimal income = sum(incomeLeaves);

    // Expense domains (incl. rendered passthrough under 'zahlungsdienst').
    Map<String, List<Leaf>> domainLeaves = new LinkedHashMap<>();
    Map<String, BigDecimal> domainTotals = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Flow>> de : expenseByDomain.entrySet()) {
      List<Leaf> leaves = new ArrayList<>();
      for (Map.Entry<String, Flow> le : de.getValue().entrySet()) {
        BigDecimal natural = le.getValue().dbit.subtract(le.getValue().crdt); // money-out
        if (natural.signum() > 0) {
          leaves.add(new Leaf(le.getKey(), labelOr(le.getValue().label, le.getKey()), natural));
        } else if (natural.signum() < 0) {
          refundsIn = refundsIn.add(natural.negate());
        }
      }
      BigDecimal total = sum(leaves);
      if (total.signum() > 0) {
        domainLeaves.put(de.getKey(), leaves);
        domainTotals.put(de.getKey(), total);
      }
    }
    BigDecimal outflow = domainTotals.values().stream().reduce(ZERO, BigDecimal::add);

    // Saving (value-level) + depot.
    List<Leaf> savingLeaves = new ArrayList<>();
    for (Map.Entry<String, Flow> e : savingByValue.entrySet()) {
      BigDecimal natural = e.getValue().dbit.subtract(e.getValue().crdt); // money-out
      if (natural.signum() > 0) {
        savingLeaves.add(new Leaf("saving:" + e.getKey(), e.getKey(), natural));
      } else if (natural.signum() < 0) {
        refundsIn = refundsIn.add(natural.negate());
      }
    }
    BigDecimal depotIncome = depot.crdt;
    BigDecimal depotBuysAmount = depot.dbit;
    boolean asSaving = params.investmentMode() == InvestmentMode.AS_SAVING;
    BigDecimal renderedDepotBuys = asSaving ? depotBuysAmount : ZERO;
    BigDecimal metaDepotBuys = asSaving ? ZERO : depotBuysAmount;

    BigDecimal saving = sum(savingLeaves).add(renderedDepotBuys);

    // Transfers + opaque passthrough (meta).
    BigDecimal internalNet = transfer.net();
    boolean renderTransfer = !params.excludeInternalTransfers();
    BigDecimal metaInternal = internalNet;
    BigDecimal metaPassthrough = passthroughOpaque.net();

    BigDecimal saldo = income.subtract(outflow).subtract(saving);

    // --- Budget conservation -------------------------------------------------
    BigDecimal budgetInputs =
        income.add(renderTransfer && internalNet.signum() > 0 ? internalNet : ZERO);
    BigDecimal budgetOutputs =
        outflow
            .add(saving)
            .add(renderTransfer && internalNet.signum() < 0 ? internalNet.negate() : ZERO);
    BigDecimal residual = budgetInputs.subtract(budgetOutputs);
    boolean hasBudget = budgetInputs.signum() > 0 || budgetOutputs.signum() > 0;
    BigDecimal budgetMainValue = budgetInputs.max(budgetOutputs);

    // --- Assemble nodes + links (fixed, deterministic order) -----------------
    List<CashflowNode> nodes = new ArrayList<>();
    List<CashflowLink> links = new ArrayList<>();

    // 1) Income sources -> budget:main (topN, remainder -> sonstiges:income).
    emitSources(nodes, links, incomeLeaves, income, params, "sonstiges:income");

    // 2) budget:main.
    if (hasBudget) {
      nodes.add(node("budget:main", "Budget", budgetMainValue, "budget"));
    }

    // 3) Expense domains: budget:main -> domain -> leaves (topN + minShare).
    List<String> domains = new ArrayList<>(domainTotals.keySet());
    domains.sort(
        Comparator.comparing((String d) -> domainTotals.get(d)).reversed()
            .thenComparing(Comparator.naturalOrder()));
    for (String domain : domains) {
      BigDecimal total = domainTotals.get(domain);
      String domainId = "domain:" + domain;
      nodes.add(node(domainId, domain, total, "domain"));
      links.add(link("budget:main", domainId, total));
      if (params.levels().contains("counterparty")) {
        emitChildren(
            nodes,
            links,
            domainId,
            domainLeaves.get(domain),
            total,
            params,
            "sonstiges:" + domain);
      }
    }

    // 4) Saving nodes: budget:main -> saving:<value> / depot:buys.
    savingLeaves.sort(leafOrder());
    for (Leaf s : savingLeaves) {
      nodes.add(node(s.id(), s.label(), s.value(), "saving"));
      links.add(link("budget:main", s.id(), s.value()));
    }
    if (renderedDepotBuys.signum() > 0) {
      nodes.add(node("depot:buys", "Depot", renderedDepotBuys, "saving"));
      links.add(link("budget:main", "depot:buys", renderedDepotBuys));
    }

    // 5) Balance / surplus.
    if (residual.signum() < 0) {
      BigDecimal need = residual.negate();
      nodes.add(node("balance:reserves", "Reserves", need, "balance"));
      links.add(link("balance:reserves", "budget:main", need));
    } else if (residual.signum() > 0) {
      nodes.add(node("budget:surplus", "Surplus", residual, "surplus"));
      links.add(link("budget:main", "budget:surplus", residual));
    }

    // 6) Rendered internal transfer.
    if (renderTransfer && internalNet.signum() != 0) {
      BigDecimal value = internalNet.abs();
      nodes.add(node("transfer:internal", "Internal transfers", value, "transfer"));
      if (internalNet.signum() < 0) {
        links.add(link("budget:main", "transfer:internal", value));
      } else {
        links.add(link("transfer:internal", "budget:main", value));
      }
    }

    CashflowExcluded excluded =
        new CashflowExcluded(
            scale(metaInternal),
            scale(metaPassthrough),
            scale(depotIncome),
            scale(metaDepotBuys),
            scale(refundsIn),
            scale(clawbacksOut));
    CashflowMeta meta =
        new CashflowMeta(scale(income), scale(outflow), scale(saving), scale(saldo), excluded);
    return new Cashflow(nodes, links, meta);
  }

  // --- Role assignment -------------------------------------------------------

  private static CashflowRole roleOf(
      CashflowRow r, Map<RoleKey, CashflowRole> roleMap, Set<String> incomePayerIds) {
    CashflowRole domainRole =
        r.domainTag() == null ? null : roleMap.get(new RoleKey("domain", r.domainTag()));
    CashflowRole natureRole =
        r.natureTag() == null ? null : roleMap.get(new RoleKey("nature", r.natureTag()));

    if (r.identityValue() != null && incomePayerIds.contains(r.identityValue())) {
      return CashflowRole.INCOME; // 1. payer-pinned override
    }
    if (natureRole == CashflowRole.PASSTHROUGH && r.attributionSource() == null) {
      return CashflowRole.PASSTHROUGH; // 2. opaque payment service
    }
    if (domainRole == CashflowRole.TRANSFER || natureRole == CashflowRole.TRANSFER) {
      return CashflowRole.TRANSFER; // 3.
    }
    if (natureRole == CashflowRole.DEPOT) {
      return CashflowRole.DEPOT; // 4.
    }
    if (domainRole == CashflowRole.SAVING || natureRole == CashflowRole.SAVING) {
      return CashflowRole.SAVING; // 5.
    }
    if (domainRole == CashflowRole.INCOME) {
      return CashflowRole.INCOME; // 6.
    }
    return CashflowRole.EXPENSE; // 7. default, bucketed by domainTag
  }

  // --- Helpers ---------------------------------------------------------------

  private static void putExpense(
      Map<String, Map<String, Flow>> expenseByDomain, String domain, CashflowRow r) {
    String leafId = r.effectiveCp() == null ? "cp:unresolved" : "cp:" + r.effectiveCp();
    expenseByDomain
        .computeIfAbsent(domain, k -> new LinkedHashMap<>())
        .computeIfAbsent(leafId, k -> new Flow())
        .add(r.direction(), r.amount(), r.label());
  }

  private static String incomeId(CashflowRow r) {
    return r.effectiveCp() == null ? "income:unresolved" : "income:" + r.effectiveCp();
  }

  private static String domainValue(String domainTag) {
    return domainTag == null ? "(untagged)" : domainTag;
  }

  /** Saving value bucket: prefer the domain tag that mapped to saving, else the nature tag. */
  private static String savingValue(CashflowRow r, Map<RoleKey, CashflowRole> roleMap) {
    if (r.domainTag() != null
        && roleMap.get(new RoleKey("domain", r.domainTag())) == CashflowRole.SAVING) {
      return r.domainTag();
    }
    return r.natureTag() != null ? r.natureTag() : "(saving)";
  }

  private static String labelOr(String label, String fallbackId) {
    if (label != null) {
      return label;
    }
    return fallbackId.endsWith(":unresolved") ? "(unresolved)" : fallbackId;
  }

  /**
   * Renders the given leaves as {@code kind:income} nodes feeding {@code budget:main}, bundling the
   * topN/minShare remainder into {@code bundleId}.
   */
  private void emitSources(
      List<CashflowNode> nodes,
      List<CashflowLink> links,
      List<Leaf> leaves,
      BigDecimal total,
      CashflowParams params,
      String bundleId) {
    leaves.sort(leafOrder());
    BigDecimal threshold = params.minShare().multiply(total);
    BigDecimal bundle = ZERO;
    int rank = 0;
    for (Leaf l : leaves) {
      boolean individual = rank < params.topN() && l.value().compareTo(threshold) >= 0;
      if (individual) {
        nodes.add(node(l.id(), l.label(), l.value(), "income"));
        links.add(link(l.id(), "budget:main", l.value()));
      } else {
        bundle = bundle.add(l.value());
      }
      rank++;
    }
    if (bundle.signum() > 0) {
      nodes.add(node(bundleId, "Sonstiges", bundle, "income"));
      links.add(link(bundleId, "budget:main", bundle));
    }
  }

  /**
   * Renders expense leaves below {@code parentId} as {@code kind:expense} nodes, bundling the
   * topN/minShare remainder into {@code bundleId} (always appended last).
   */
  private void emitChildren(
      List<CashflowNode> nodes,
      List<CashflowLink> links,
      String parentId,
      List<Leaf> leaves,
      BigDecimal total,
      CashflowParams params,
      String bundleId) {
    leaves.sort(leafOrder());
    BigDecimal threshold = params.minShare().multiply(total);
    BigDecimal bundle = ZERO;
    int rank = 0;
    for (Leaf l : leaves) {
      boolean individual = rank < params.topN() && l.value().compareTo(threshold) >= 0;
      if (individual) {
        nodes.add(node(l.id(), l.label(), l.value(), "expense"));
        links.add(link(parentId, l.id(), l.value()));
      } else {
        bundle = bundle.add(l.value());
      }
      rank++;
    }
    if (bundle.signum() > 0) {
      nodes.add(node(bundleId, "Sonstiges", bundle, "expense"));
      links.add(link(parentId, bundleId, bundle));
    }
  }

  /** Sort leaves by value DESC, then id ASC. */
  private static Comparator<Leaf> leafOrder() {
    return Comparator.comparing(Leaf::value).reversed().thenComparing(Leaf::id);
  }

  private static BigDecimal sum(List<Leaf> leaves) {
    return leaves.stream().map(Leaf::value).reduce(ZERO, BigDecimal::add);
  }

  private static CashflowNode node(String id, String label, BigDecimal value, String kind) {
    return new CashflowNode(id, label, scale(value), kind);
  }

  private static CashflowLink link(String source, String target, BigDecimal value) {
    return new CashflowLink(source, target, scale(value));
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP);
  }
}
