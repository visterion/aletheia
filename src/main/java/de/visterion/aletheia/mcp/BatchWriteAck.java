package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * Acknowledgement returned by batch {@link WriteTools} methods (spec §5, batch {@code
 * classify_counterparty}).
 *
 * @param affectedCount the number of counterparties the batch write applied to
 * @param dimensions the distinct tag dimensions that were set/replaced
 */
public record BatchWriteAck(int affectedCount, List<String> dimensions) {}
