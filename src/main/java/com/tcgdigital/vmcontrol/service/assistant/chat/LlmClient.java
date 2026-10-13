package com.tcgdigital.vmcontrol.service.assistant.chat;

import java.util.List;
import java.util.Map;

/**
 * A chat model with tool use (E36-T05), behind one provider-neutral shape so the organisation can
 * pick its approved provider: {@code anthropic} (Messages API) or {@code openai} (any OpenAI-compatible
 * chat completions endpoint, such as a Bedrock or Azure gateway).
 */
public interface LlmClient {

    /** The assistant.chat.provider value that selects this client. */
    String provider();

    /** One model call; throws {@link LlmException} when the provider fails or answers something unusable. */
    Reply complete(Request request);

    record Request(String endpoint, String apiKey, String model, int maxTokens, String system,
                   List<Turn> turns, List<ToolSpec> tools) {
    }

    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    }

    record ToolCall(String id, String name, Map<String, Object> arguments) {
    }

    record ToolResult(String callId, String text, boolean error) {
    }

    /** A conversation turn: the user's text, the model's reply (with any tool calls), or the tools' results. */
    sealed interface Turn permits UserText, AssistantReply, ToolResults {
    }

    record UserText(String text) implements Turn {
    }

    record AssistantReply(String text, List<ToolCall> toolCalls) implements Turn {
    }

    record ToolResults(List<ToolResult> results) implements Turn {
    }

    /** The model's answer: text, and the tools it wants called (none when it has finished). */
    record Reply(String text, List<ToolCall> toolCalls) {
        public Reply {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
