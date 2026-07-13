package de.visterion.aletheia.ingest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/** Source-agnostic content hash for transaction deduplication (spec §3). */
public final class ContentHash {

  private ContentHash() {}

  public static String hashHex(
      String accountKey, String amtCcy, String bookgDt, String amt, String cdtDbtInd,
      String rmtdNm, String rmtInf, String mndtId, String endToEndId) {
    String canonical =
        canonical(accountKey, amtCcy, bookgDt, amt, cdtDbtInd, rmtdNm, rmtInf, mndtId, endToEndId);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static String canonical(
      String accountKey, String amtCcy, String bookgDt, String amt, String cdtDbtInd,
      String rmtdNm, String rmtInf, String mndtId, String endToEndId) {
    return String.join(
        "|",
        escape(normText(accountKey)),
        escape(normText(amtCcy)),
        escape(normText(bookgDt)),
        escape(normalizeAmount(amt)),
        escape(normText(cdtDbtInd)),
        escape(normText(rmtdNm)),
        escape(normText(rmtInf)),
        escape(normText(mndtId)),
        escape(normText(endToEndId)));
  }

  /** Parses Amt to a 2-decimal plain string; fails loud on > 2 significant decimals. */
  public static String normalizeAmount(String amt) {
    if (amt == null || amt.isBlank()) {
      throw new IllegalArgumentException("amount is required");
    }
    BigDecimal value = new BigDecimal(amt.trim());
    if (value.stripTrailingZeros().scale() > 2) {
      throw new IllegalArgumentException("amount has more than 2 significant decimals: " + amt);
    }
    return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
  }

  /** null -> ""; NFC; trim; collapse internal whitespace to single spaces. */
  private static String normText(String s) {
    if (s == null) {
      return "";
    }
    String n = Normalizer.normalize(s, Normalizer.Form.NFC).trim();
    return n.replaceAll("\\s+", " ");
  }

  /** Backslash-escape '\' and '|' so delimiters never collide with field content. */
  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("|", "\\|");
  }
}
