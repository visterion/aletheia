package de.visterion.aletheia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the {@link ToolPermissionService} allow-list (no Spring context needed: the
 * service holds only static data). Covers Task 14's rename of the confirm/dismiss write tools to
 * confirm_counterparty/dismiss_counterparty.
 */
class ToolPermissionServiceTest {

  private final ToolPermissionService service = new ToolPermissionService();

  @Test
  void writerToolsIncludeTheRenamedConfirmAndDismissTools() {
    var allowed = service.allowedTools(AuthRole.WRITER);

    assertThat(allowed).contains("confirm_counterparty", "dismiss_counterparty");
    assertThat(allowed).doesNotContain("confirm", "dismiss");
  }
}
