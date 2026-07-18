package de.visterion.aletheia.mcp.transport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpResponseTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void toolResultSerializesContentAsTextBlock() {
    McpResponse response = McpResponse.toolResult("7", "{\"a\":1}");

    JsonNode json = mapper.valueToTree(response);

    assertThat(json.get("jsonrpc").asString()).isEqualTo("2.0");
    assertThat(json.get("id").asString()).isEqualTo("7");
    assertThat(json.get("result").get("content").get(0).get("type").asString()).isEqualTo("text");
    assertThat(json.get("result").get("content").get(0).get("text").asString())
        .isEqualTo("{\"a\":1}");
    assertThat(json.has("error")).isFalse();
  }

  @Test
  void toolExecutionErrorAddsIsErrorFlag() {
    McpResponse response = McpResponse.toolExecutionError("7", "boom");

    JsonNode json = mapper.valueToTree(response);

    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
    assertThat(json.get("result").get("content").get(0).get("text").asString()).isEqualTo("boom");
  }

  @Test
  void methodNotFoundUsesJsonRpcCodeMinus32601() {
    McpResponse response = McpResponse.methodNotFound("7", "unknown/method");

    assertThat(response.error().code()).isEqualTo(-32601);

    JsonNode json = mapper.valueToTree(response);
    assertThat(json.get("error").get("code").asInt()).isEqualTo(-32601);
    assertThat(json.has("result")).isFalse();
  }

  @Test
  void successWithNullIdStillSerializesIdKey() {
    McpResponse response = McpResponse.success(null, "ok");

    JsonNode json = mapper.valueToTree(response);

    assertThat(json.has("id")).isTrue();
    assertThat(json.get("id").isNull()).isTrue();
  }
}
