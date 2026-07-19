package de.visterion.aletheia.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Sankey-ready money-flow result of the {@code cashflow} tool. */
public record Cashflow(List<CashflowNode> nodes, List<CashflowLink> links, CashflowMeta meta) {

  public record CashflowNode(String id, String label, BigDecimal value, String kind) {}

  public record CashflowLink(String source, String target, BigDecimal value) {}

  public record CashflowMeta(
      BigDecimal income,
      BigDecimal outflow,
      BigDecimal saving,
      BigDecimal saldo,
      CashflowExcluded excluded) {}

  public record CashflowExcluded(
      BigDecimal internal,
      BigDecimal passthrough,
      BigDecimal depotIncome,
      BigDecimal depotBuys,
      BigDecimal refundsIn,
      BigDecimal clawbacksOut) {}

  /** One logical leaf booking, already identity-resolved and single-tag-resolved by the fetch. */
  public record CashflowRow(
      Long effectiveCp,        // null = unresolved (no counterparty)
      String label,            // COALESCE(display_name_override, display_name); null for unresolved
      String domainTag,        // single domain tag value; null if none
      String natureTag,        // single nature tag value; null if none
      String direction,        // "DBIT" | "CRDT"
      BigDecimal amount,       // always positive
      String remittanceInfo,   // nullable
      String attributionSource,// "paypal" | "manual" | null
      String identityValue) {} // identity_value of the effective counterparty (for payer match)

  public record CashflowParams(
      LocalDate dateFrom,
      LocalDate dateTo,
      List<String> levels,
      boolean excludeInternalTransfers,
      boolean excludePassthroughs,
      InvestmentMode investmentMode,
      int topN,
      BigDecimal minShare) {}

  public enum InvestmentMode {
    AS_SAVING,
    EXCLUDE;

    /** Wire values are lowercase (`as_saving`|`exclude`); defaults to AS_SAVING. */
    public static InvestmentMode fromWire(String wire) {
      if (wire == null || wire.equals("as_saving")) {
        return AS_SAVING;
      }
      if (wire.equals("exclude")) {
        return EXCLUDE;
      }
      throw new IllegalArgumentException("investmentMode must be 'as_saving' or 'exclude'");
    }
  }

  public enum CashflowRole {
    INCOME,
    SAVING,
    TRANSFER,
    DEPOT,
    PASSTHROUGH,
    EXPENSE
  }

  /** Key into the role map: (dimension, value). */
  public record RoleKey(String dimension, String value) {}
}
