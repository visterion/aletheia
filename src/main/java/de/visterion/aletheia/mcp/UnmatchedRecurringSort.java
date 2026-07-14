package de.visterion.aletheia.mcp;

/**
 * {@code list_unmatched_recurring} sort order (spec §5, TP1 Task 6). Default is unsorted
 * (insertion order: unlinked mandate contracts first, then mandate-less series).
 */
public enum UnmatchedRecurringSort {
  annual_cost_desc
}
