package de.visterion.aletheia.mcp;

import java.math.BigDecimal;

/**
 * One child allocation for {@code split_transaction}.
 *
 * @param counterpartyId existing counterparty id, or null when creating by displayName
 * @param displayName name-based counterparty to create/ensure when counterpartyId is null
 * @param mandateId optional mandate override for the child row
 * @param amount positive allocation amount; sum across allocations must equal parent amount
 * @param remittanceInfo optional remittance info for the child row
 */
public record Allocation(
    Long counterpartyId,
    String displayName,
    String mandateId,
    BigDecimal amount,
    String remittanceInfo) {}
