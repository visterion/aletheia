package de.visterion.aletheia.auth;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Role -> allowed-tool-name map for Aletheia's MCP tools (spec §5/§6). Rewritten from
 * HiveMem's version (adversarial review M4): a literal port of HiveMem's tool-name allow-list
 * would deny every Aletheia tool (fails closed, unusable), since the tool sets are entirely
 * different between the two servers.
 */
@Service
public class ToolPermissionService {

  private static Set<String> tools(String... toolNames) {
    return Set.of(toolNames);
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> combined = new HashSet<>(first);
    combined.addAll(second);
    return Set.copyOf(combined);
  }

  /** Read-scope tools (spec §5 "Read"). */
  private static final Set<String> READ_TOOLS =
      tools(
          "list_counterparties",
          "get_review_queue",
          "list_unmatched_recurring",
          "counterparty_transactions",
          "sql_query",
          "describe_schema");

  /** Additional write-scope tools (spec §5 "Write"), on top of everything READER can do. */
  private static final Set<String> WRITE_TOOLS =
      tools("classify_counterparty", "mark_recurring", "confirm", "link_contract", "dismiss");

  private static final Set<String> WRITER_TOOLS = union(READ_TOOLS, WRITE_TOOLS);

  /** No Aletheia-specific admin-only tools exist yet; ADMIN gets everything WRITER gets. */
  private static final Set<String> ALL_TOOLS = WRITER_TOOLS;

  private static final Map<AuthRole, Set<String>> ROLE_TOOLS =
      Map.of(
          AuthRole.ADMIN, ALL_TOOLS,
          AuthRole.WRITER, WRITER_TOOLS,
          AuthRole.READER, READ_TOOLS);

  public boolean isAllowed(AuthRole role, String toolName) {
    return allowedTools(role).contains(toolName);
  }

  public Set<String> allowedTools(AuthRole role) {
    if (role == null) {
      return Set.of();
    }
    return ROLE_TOOLS.getOrDefault(role, Set.of());
  }
}
