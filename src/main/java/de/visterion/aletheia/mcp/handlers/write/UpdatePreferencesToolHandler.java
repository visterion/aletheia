package de.visterion.aletheia.mcp.handlers.write;

import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.mcp.ArgumentParser;
import de.visterion.aletheia.mcp.ToolHandler;
import de.visterion.aletheia.mcp.ToolInputSchema;
import de.visterion.aletheia.mcp.WriteTools;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Hand-rolled {@code update_preferences} write tool handler; delegates to {@link
 * WriteTools#updatePreferences(String)}.
 */
@Component
@Order(13)
public class UpdatePreferencesToolHandler implements ToolHandler {

  private final WriteTools writeTools;

  public UpdatePreferencesToolHandler(WriteTools writeTools) {
    this.writeTools = writeTools;
  }

  @Override
  public String name() {
    return "update_preferences";
  }

  @Override
  public String description() {
    return "Record durable customer preferences (markdown). Replaces the preferences section only"
        + " -- the operating guide is protected. wake_up first, edit, write back the full"
        + " preferences markdown.";
  }

  @Override
  public Map<String, Object> inputSchema() {
    return ToolInputSchema.object()
        .requiredString("preferences", "the full new preferences markdown")
        .build();
  }

  @Override
  public Object call(AuthPrincipal principal, JsonNode arguments) {
    String preferences = ArgumentParser.requiredText(arguments, "preferences");
    return writeTools.updatePreferences(preferences);
  }
}
