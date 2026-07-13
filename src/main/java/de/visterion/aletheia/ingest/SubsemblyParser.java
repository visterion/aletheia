package de.visterion.aletheia.ingest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Parses a Subsembly JSON export (array of booking objects). */
@Component
public class SubsemblyParser {

  private final ObjectMapper mapper = new ObjectMapper();

  public List<SubsemblyBooking> parse(InputStream json) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("malformed JSON in Subsembly export", e);
    }
    if (root == null || !root.isArray()) {
      throw new IllegalArgumentException("export root must be a JSON array");
    }
    List<SubsemblyBooking> rows = new ArrayList<>(root.size());
    for (JsonNode node : root) {
      rows.add(
          new SubsemblyBooking(
              text(node, "Id"),
              text(node, "AcctId"),
              text(node, "OwnrAcctIBAN"),
              text(node, "Amt"),
              text(node, "AmtCcy"),
              text(node, "CdtDbtInd"),
              text(node, "BookgDt"),
              text(node, "ValDt"),
              text(node, "BookgSts"),
              text(node, "BookgTxt"),
              text(node, "RmtInf"),
              text(node, "GVC"),
              text(node, "GVCExtension"),
              text(node, "PurpCd"),
              text(node, "RmtdNm"),
              text(node, "RmtdUltmtNm"),
              text(node, "RmtdAcctIBAN"),
              text(node, "RmtdAcctBIC"),
              text(node, "CdtrId"),
              text(node, "MndtId"),
              text(node, "EndToEndId"),
              node));
    }
    return rows;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }
}
