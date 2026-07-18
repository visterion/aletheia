package de.visterion.aletheia.mcp;

/** Acknowledgement for tag-rule lifecycle tools (enable/delete). {@code ruleId}, not counterpartyId. */
public record TagRuleAck(long ruleId, String message) {}
