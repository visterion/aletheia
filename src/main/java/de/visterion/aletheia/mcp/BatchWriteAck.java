package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * Acknowledgement returned by batch/dual-mode {@link WriteTools} methods.
 *
 * @param affectedCount the number of counterparties the write applied to
 * @param details for classify: the tag dimensions set; for dismiss/confirm: one
 *     summary line (batch) or the single-item message.
 */
public record BatchWriteAck(int affectedCount, List<String> details) {}
