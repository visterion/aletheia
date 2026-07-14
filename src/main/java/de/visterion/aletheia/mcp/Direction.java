package de.visterion.aletheia.mcp;

/**
 * Booking direction (camt.053 / Subsembly wire values {@code DBIT}/{@code CRDT}), plus {@code
 * BOTH} for tools that aggregate across direction. Not yet wired to a tool (introduced for a
 * later task).
 */
public enum Direction {
  DBIT,
  CRDT,
  BOTH
}
