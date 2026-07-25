package de.visterion.aletheia.mcp;

import java.util.List;
import java.util.Map;

/**
 * The canonical start vocabulary for counterparty tags, per dimension.
 *
 * <p>This is the documented answer to "which tag value should I use?" -- without it, automatic
 * classification invents synonyms for values that already exist. It is deliberately a code constant
 * and not a database table: it is reviewed like code, and the dimension set itself is fixed by the
 * {@code CHECK (dimension IN ('domain', 'nature', 'necessity'))} constraint on {@code
 * counterparty_tags} (V3).
 *
 * <p>The seed is not exhaustive and does not restrict what may be stored -- {@code taxonomy} reports
 * the emergent values separately, so drift between canon and reality stays visible. The values
 * {@code domain:einkommen}, {@code domain:transfer-privat} and {@code nature:investment} are
 * load-bearing: {@code V17__cashflow_role_map.sql} maps them to cashflow roles.
 */
public final class SeedVocabulary {

  private static final List<String> DIMENSIONS = List.of("domain", "nature", "necessity");

  private static final Map<String, List<String>> VALUES =
      Map.of(
          "domain",
              List.of(
                  "versicherung",
                  "energie",
                  "wohnen",
                  "telekommunikation",
                  "mobilitaet",
                  "lebensmittel",
                  "bildung",
                  "finanzen",
                  "freizeit",
                  "einkommen",
                  "transfer-privat"),
          "nature", List.of("fixkosten", "variabel", "zahlungsdienst", "umbuchung", "investment"),
          "necessity", List.of("pflicht", "wichtig", "optional"));

  private SeedVocabulary() {}

  /** The tag dimensions, in the order {@code taxonomy} reports them. */
  public static List<String> dimensions() {
    return DIMENSIONS;
  }

  /** The canonical values for one dimension; empty for an unknown dimension. */
  public static List<String> valuesFor(String dimension) {
    return VALUES.getOrDefault(dimension, List.of());
  }
}
