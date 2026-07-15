package de.visterion.aletheia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.visterion.aletheia.auth.AuthRole;
import de.visterion.aletheia.auth.ToolPermissionService;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Guards the wiring between the {@code @Tool}-annotated MCP methods on {@link ReadTools}/{@link
 * WriteTools} and {@link ToolPermissionService}'s role-to-tool-name allow-list. A tool whose
 * {@code @Tool(name=...)} is missing from the WRITER union would be silently denied at runtime
 * (fails closed) -- this test would catch that regression, and an accidentally dropped or
 * duplicated tool registration (guarded by the exact count of 16).
 */
class ToolPermissionCoverageTest {

  private static Set<String> toolNamesOf(Class<?> toolClass) {
    Set<String> names = new HashSet<>();
    for (Method method : toolClass.getDeclaredMethods()) {
      Tool annotation = method.getAnnotation(Tool.class);
      if (annotation != null) {
        names.add(annotation.name());
      }
    }
    return names;
  }

  @Test
  void everyDeclaredToolIsCoveredByThePermissionServiceAndThereAreExactlySixteen() {
    Set<String> readToolNames = toolNamesOf(ReadTools.class);
    Set<String> writeToolNames = toolNamesOf(WriteTools.class);

    Set<String> allToolNames = new HashSet<>(readToolNames);
    allToolNames.addAll(writeToolNames);

    assertThat(readToolNames.size() + writeToolNames.size())
        .as("no tool name is duplicated between ReadTools and WriteTools")
        .isEqualTo(allToolNames.size());
    assertThat(allToolNames).as("exactly 16 MCP tools are registered").hasSize(16);

    Set<String> allowedForWriter = new ToolPermissionService().allowedTools(AuthRole.WRITER);

    assertThat(allowedForWriter)
        .as("every declared @Tool name must be present in the WRITER permission union")
        .containsAll(allToolNames);
  }

  @Test
  void splitTransactionIsRegisteredAsWriteTool() {
    Set<String> allowedForWriter = new ToolPermissionService().allowedTools(AuthRole.WRITER);
    assertThat(allowedForWriter).contains("split_transaction");
    // readers must not get it (scope enforcement)
    Set<String> allowedForReader = new ToolPermissionService().allowedTools(AuthRole.READER);
    assertThat(allowedForReader).doesNotContain("split_transaction");
  }

  @Test
  void obligationsRegisterDescriptionDocumentsPaymentServiceExclusion() {
    Tool annotation = null;
    for (Method method : ReadTools.class.getDeclaredMethods()) {
      Tool candidate = method.getAnnotation(Tool.class);
      if (candidate != null && candidate.name().equals("obligations_register")) {
        annotation = candidate;
        break;
      }
    }

    assertThat(annotation).isNotNull();
    assertThat(annotation.description())
        .contains(
            "Excludes counterparties tagged with confirmed nature:zahlungsdienst; "
                + "auto tags do not exclude.");
  }
}
