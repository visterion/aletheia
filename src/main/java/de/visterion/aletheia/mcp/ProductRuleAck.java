package de.visterion.aletheia.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Acknowledgement of every product-rule lifecycle tool. {@code ruleId}, not counterpartyId.
 *
 * <p>{@code blastRadius} is filled by create/update, {@code revert} by delete; the other one is
 * {@code null} and omitted from the JSON (the {@code ReviewQueueEntry} precedent), so a caller never
 * has to read past a block of nulls to find the one that applies.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductRuleAck(
    Long ruleId,
    String message,
    boolean dryRun,
    ProductRuleBlastRadius blastRadius,
    ProductRuleRevert revert) {}
