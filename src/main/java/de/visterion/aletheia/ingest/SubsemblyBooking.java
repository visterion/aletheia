package de.visterion.aletheia.ingest;

import tools.jackson.databind.JsonNode;

/** One parsed Subsembly export row plus its untouched source node. */
public record SubsemblyBooking(
    String id,
    String acctId,
    String ownrAcctIban,
    String amt,
    String amtCcy,
    String cdtDbtInd,
    String bookgDt,
    String valDt,
    String bookgSts,
    String bookgTxt,
    String rmtInf,
    String gvc,
    String gvcExtension,
    String purpCd,
    String rmtdNm,
    String rmtdUltmtNm,
    String rmtdAcctIban,
    String rmtdAcctBic,
    String cdtrId,
    String mndtId,
    String endToEndId,
    JsonNode raw) {

  public boolean isBooked() {
    return "BOOK".equals(bookgSts);
  }

  public String accountKey() {
    return (ownrAcctIban != null && !ownrAcctIban.isBlank()) ? ownrAcctIban : acctId;
  }

  public String contentHash() {
    return ContentHash.hashHex(
        accountKey(), amtCcy, bookgDt, amt, cdtDbtInd, rmtdNm, rmtInf, mndtId, endToEndId);
  }
}
