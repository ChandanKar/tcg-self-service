package com.tcgdigital.vmcontrol.service.assistant.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic's Messages API (E36-T05): POST {endpoint}/v1/messages with tool definitions. */
@Component
public class AnthropicLlmClient implements LlmClient {

    static final String API_VERSION = "2023-06-01";

    private final LlmHttp http;

    public AnthropicLlmClient(LlmHttp http) {
        this.http = http;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public Reply complete(Request request) {
        String endpoint = request.endpoint().replaceAll("/+$", "");
        JsonNode answer = http.post(endpoint + "/v1/messages",
                Map.of("x-api-key", request.apiKey(), "anthropic-version", API_VERSION), body(request));
        return parse(answer, http.mapper());
    }

    static Map<String, Object> body(Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.system());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Turn turn : request.turns()) {
            if (turn instanceof UserText u) {
                messages.add(Map.of("role", "user", "content", u.text()));
            } else if (turn instanceof AssistantReply a) {
                List<Map<String, Object>> content = new ArrayList<>();
                if (!a.text().isBlank()) content.add(Map.of("type", "text", "text", a.text()));
                a.toolCalls().forEach(c -> content.add(Map.of("type", "tool_use", "id", c.id(), "name", c.name(),
                        "input", c.arguments())));
                messages.add(Map.of("role", "assistant", "content", content));
            } else if (turn instanceof ToolResults r) {
                messages.add(Map.of("role", "user", "content", r.results().stream()
                        .map(x -> Map.<String, Object>of("type", "tool_result", "tool_use_id", x.callId(), "content", x.text(),
                                "is_error", x.error()))
                        .toList()));
            }
        }
        body.put("messages", messages);
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream()
                    .map(t -> Map.of("name", t.name(), "description", t.description(), "input_schema", t.inputSchema()))
                    .toList());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    static Reply parse(JsonNode answer, ObjectMapper mapper) {
        JsonNode content = answer.path("content");
        if (!content.isArray()) throw new LlmException("Anthropic answer without content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : content) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(text.isEmpty() ? "" : "\n").append(block.path("text").asText());
                case "tool_use" -> calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(),
                        block.path("input").isObject() ? mapper.convertValue(block.path("input"), Map.class) : Map.of()));
                default -> { }
            }
        }
        return new Reply(text.toString(), calls);
    }
}
