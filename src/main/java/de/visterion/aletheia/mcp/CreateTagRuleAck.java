package de.visterion.aletheia.mcp;

import java.util.List;

/**
 * Result of {@code create_tag_rule}. For {@code dryRun=true}: {@code ruleId=null},
 * {@code matchCount}/{@code wouldChangeCount}/{@code sampleCounterparties}/{@code wouldSetTags}
 * filled, {@code appliedCount=0}. For a real create: {@code ruleId} set, {@code appliedCount} = rows
 * changed by backfill (0 if {@code backfill=false}).
 */
public record CreateTagRuleAck(
    Long ruleId,
    int matchCount,
    int wouldChangeCount,
    int appliedCount,
    List<CounterpartySample> sampleCounterparties,
    List<String> wouldSetTags) {}
