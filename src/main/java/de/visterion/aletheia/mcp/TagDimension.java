package de.visterion.aletheia.mcp;

/**
 * The tagging dimensions a counterparty can be classified on (spec §5). Not yet wired to {@link
 * TagInput#dimension}, which stays {@code String} for now (see task 13).
 */
public enum TagDimension {
  domain,
  nature,
  necessity
}
