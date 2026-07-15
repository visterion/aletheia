package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * Acknowledgement returned by {@code split_transaction}.
 *
 * @param unsplitPerformed true when children were deleted (unsplit path)
 * @param allocationsCreated number of child rows inserted
 * @param createdCounterpartyIds ids of name-based counterparties created during this call
 * @param message short human-readable confirmation
 */
public record SplitTransactionAck(
    boolean unsplitPerformed,
    int allocationsCreated,
    List<Long> createdCounterpartyIds,
    String message) {}
