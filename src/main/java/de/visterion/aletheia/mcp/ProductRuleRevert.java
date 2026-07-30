package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * What {@code delete_product_rule} removes, or would remove on a dry run (spec §5, "rule deleted").
 *
 * <p>{@code keptConfirmedContracts} is the warning half: a product contract a human confirmed,
 * dismissed or ended is <b>never</b> destroyed by a tool call, so it survives the revert and is
 * named here instead. It then describes a product that no booking carries any more and has to be
 * cleaned up deliberately.
 *
 * @param childrenRemoved product split children deleted; human split children are never touched
 * @param stampsCleared roots whose {@code product}/{@code product_policy_no} stamp was cleared
 * @param autoContractsDeleted strictly-auto ({@code source='auto'}, {@code status='open'}) product
 *     contracts deleted
 * @param recurringSeriesDeleted recurring rows deleted with those contracts
 * @param mandateContractsReopened {@code ended} mandate-level ({@code product IS NULL}) contracts
 *     set back to {@code open} with their {@code end_date} cleared
 * @param keptConfirmedContracts human-decided product contracts left in place, one line each
 */
public record ProductRuleRevert(
    int childrenRemoved,
    int stampsCleared,
    int autoContractsDeleted,
    int recurringSeriesDeleted,
    int mandateContractsReopened,
    List<String> keptConfirmedContracts) {}
