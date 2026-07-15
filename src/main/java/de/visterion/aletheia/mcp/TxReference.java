package de.visterion.aletheia.mcp;

/**
 * Natural-key reference to a transaction (content_hash + occurrence_index).
 *
 * @param contentHash SHA-256 content hash of the transaction
 * @param occurrenceIndex disambiguator for hash collisions (usually 0)
 */
public record TxReference(String contentHash, int occurrenceIndex) {}
